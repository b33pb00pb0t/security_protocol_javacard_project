package tools;

import applet.MembershipApplet;
import applet.ProtocolConstants;
import backend.ApduDateCodec;
import backend.BlockListRepository;
import backend.CardGateway;
import backend.CardId;
import backend.CsvBlockListRepository;
import backend.CsvMemberRepository;
import backend.JCardSimGateway;
import backend.TerminalSyncService;
import backend.Tier2ReceiptVerifier;
import com.licel.jcardsim.base.Simulator;
import frontend.ConnectedTerminalService;
import javacard.framework.AID;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.LocalDate;
import java.util.Arrays;

public final class SimulatorRegressionTest {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;
    private static final byte INS_ADMIN_CHALLENGE = (byte) 0x30;
    private static final byte[] APPLET_AID = new byte[] {
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x01
    };

    private SimulatorRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        runConnectedServiceFlow();
        runRawApduNegativeChecks();
        runRawTier2ReceiptChecks();
        System.out.println("SIMULATOR REGRESSION: PASS");
    }

    private static void runConnectedServiceFlow() throws Exception {
        Path directory = Files.createTempDirectory("membership-simulator-regression-");
        CsvMemberRepository members = new CsvMemberRepository(directory.resolve("members.csv").toString());
        BlockListRepository blocks = new CsvBlockListRepository(directory.resolve("blocked.csv").toString());
        JCardSimGateway gateway = new JCardSimGateway();
        ConnectedTerminalService service = new ConnectedTerminalService(
                members, blocks, new TerminalSyncService(blocks), gateway);
        String memberId = "1234";
        LocalDate today = LocalDate.now();

        requireContains(service.initializeCard(), "blank card", "provision");
        requireContains(service.activateCard(memberId, today.plusYears(2).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
                "activated", "activate");
        requireContains(service.syncTerminals(), "synced", "terminal sync");
        requireContains(service.checkInTier1(memberId), "ACCESS GRANTED", "Tier 1");
        requireContains(service.checkInTier2(memberId), "DailyCounter=1", "Tier 2 first access");
        requireContains(service.checkInTier2(memberId), "DailyCounter=2", "Tier 2 second access");
        requireContains(service.checkInTier2(memberId), "ACCESS DENIED", "Tier 2 third access");

        CardGateway.CardAccessResult nextDay = gateway.checkInTier2(memberId, today.plusDays(1));
        require(nextDay.isSuccess() && nextDay.getMessage().contains("DailyCounter=1"),
                "Tier 2 next-day counter reset failed: " + nextDay.getMessage());

        requireContains(service.blockCard(memberId), "Block List", "block");
        requireContains(service.syncTerminals(), "synced", "post-block terminal sync");
        requireContains(service.checkInTier1(memberId), "ACCESS DENIED", "post-block access policy");
        requireContains(service.activateCard(memberId, today.plusYears(2)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)), "BLOCKED", "blocked activation policy");
        System.out.println("Connected service flow: PASS");
    }

    private static void runRawApduNegativeChecks() {
        Simulator wrongLengthSimulator = newSimulator();
        requireSw(transmit(wrongLengthSimulator, INS_ACTIVATE, CardId.toBytes("1234")),
                0x6700, "ACTIVATE with four bytes");

        RawCard wrongInstructionCard = newProvisionedCard("1234");
        requireSw(authenticatedActivate(wrongInstructionCard, "1234", LocalDate.now(), LocalDate.now().plusYears(1)),
                0x9000, "valid authenticated ACTIVATE before wrong Tier 2 INS check");
        requireSw(transmit(wrongInstructionCard.simulator, INS_T2_STEP1,
                new byte[ProtocolConstants.TIER2_STEP2_PAYLOAD_LENGTH]), 0x6700,
                "Tier 2 step 2 payload sent with step 1 INS");

        requireSw(authenticatedBlock(wrongInstructionCard, "1234"), 0x9000, "authenticated BLOCK");
        requireSw(authenticatedActivate(wrongInstructionCard, "1234", LocalDate.now(), LocalDate.now().plusYears(1)),
                0x6985,
                "blocked card reactivation");
        requireSw(transmit(wrongInstructionCard.simulator, (byte) 0x20, new byte[16]), 0x6985,
                "blocked card Tier 1");

        RawCard unauthenticatedAdminCard = newProvisionedCard("1234");
        requireSw(transmit(unauthenticatedAdminCard.simulator, INS_ACTIVATE,
                legacyActivationPayload(LocalDate.now(), LocalDate.now().plusYears(1))),
                0x6700, "unauthenticated ACTIVATE");
        requireSw(authenticatedActivate(unauthenticatedAdminCard, "1234", LocalDate.now(), LocalDate.now().plusYears(1)),
                0x9000, "authenticated ACTIVATE");
        requireSw(transmit(unauthenticatedAdminCard.simulator, INS_BLOCK, null),
                0x6700, "unauthenticated BLOCK");

        RawCard nonAdminCard = newProvisionedCard("1234");
        requireSw(authenticatedActivateWithRole(nonAdminCard, "1234", LocalDate.now(), LocalDate.now().plusYears(1),
                ProtocolConstants.ROLE_CONTROLLED_ACCESS_TERMINAL), 0x6982,
                "ACTIVATE signed by non-admin role");

        RawCard replayCard = newProvisionedCard("1234");
        byte[] replayOperation = activationOperationData("1234", LocalDate.now(), LocalDate.now().plusYears(1));
        ApduResponse firstNonce = transmitResponse(replayCard.simulator, INS_ADMIN_CHALLENGE, null);
        requireSw(firstNonce.sw, 0x9000, "first admin challenge");
        byte[] replayPayload = authenticatedAdminPayload(replayCard, replayOperation,
                replayCard.adminKeyPair, ProtocolConstants.ROLE_ADMIN_TERMINAL, firstNonce.data);
        requireSw(transmit(replayCard.simulator, INS_ADMIN_CHALLENGE, null), 0x9000, "second admin challenge");
        requireSw(transmit(replayCard.simulator, INS_ACTIVATE, replayPayload), 0x6982,
                "ACTIVATE with replayed old admin nonce");

        RawCard wrongIdCard = newProvisionedCard("1234");
        requireSw(authenticatedActivate(wrongIdCard, "1234", LocalDate.now(), LocalDate.now().plusYears(1)),
                0x9000, "ACTIVATE before wrong member block");
        requireSw(authenticatedBlock(wrongIdCard, "1235"), 0x6982,
                "BLOCK with wrong assigned member ID");

        RawCard malformedDateCard = newProvisionedCard("1234");
        byte[] malformedDateActivation = activationOperationData("1234", LocalDate.now(), LocalDate.now().plusYears(1));
        Arrays.fill(malformedDateActivation, 5, 13, (byte) 0xFA);
        requireSw(transmit(malformedDateCard.simulator, INS_ACTIVATE,
                authenticatedAdminPayloadWithChallenge(malformedDateCard, malformedDateActivation,
                        malformedDateCard.adminKeyPair, ProtocolConstants.ROLE_ADMIN_TERMINAL)), 0x9000,
                "malformed BCD activation date");
        System.out.println("Known limitation: applet accepts malformed BCD dates.");
        System.out.println("Raw APDU negative checks: PASS");
    }

    private static void runRawTier2ReceiptChecks() {
        RawCard card = newProvisionedCard("1234");
        LocalDate today = LocalDate.now();
        requireSw(authenticatedActivate(card, "1234", today, today.plusYears(1)),
                0x9000, "raw receipt ACTIVATE");

        Tier2Exchange exchange = runRawTier2Exchange(card, "1234", today);
        require(exchange.receipt.getDailyCounter() == 1,
                "Tier 2 receipt daily counter was " + exchange.receipt.getDailyCounter());
        require(exchange.receipt.getTransactionCounter() == 1,
                "Tier 2 receipt transaction counter was " + exchange.receipt.getTransactionCounter());

        byte[] tamperedCounterReceipt = Arrays.copyOf(exchange.receiptBytes, exchange.receiptBytes.length);
        tamperedCounterReceipt[1] = (byte) (tamperedCounterReceipt[1] + 1);
        requireReceiptRejected(tamperedCounterReceipt, exchange, exchange.date,
                exchange.terminalId, "tampered Tier 2 receipt counter");

        byte[] wrongDate = ApduDateCodec.encode(today.plusDays(1));
        requireReceiptRejected(exchange.receiptBytes, exchange, wrongDate,
                exchange.terminalId, "Tier 2 receipt with wrong date");

        byte[] wrongTerminalId = Arrays.copyOf(exchange.terminalId, exchange.terminalId.length);
        wrongTerminalId[3] = (byte) (wrongTerminalId[3] ^ 0x01);
        requireReceiptRejected(exchange.receiptBytes, exchange, exchange.date,
                wrongTerminalId, "Tier 2 receipt with wrong terminal ID");

        System.out.println("Raw Tier 2 receipt checks: PASS");
    }

    private static Simulator newSimulator() {
        Simulator simulator = new Simulator();
        AID aid = new AID(APPLET_AID, (short) 0, (byte) APPLET_AID.length);
        simulator.installApplet(aid, MembershipApplet.class);
        require(simulator.selectApplet(aid), "Could not select simulator applet");
        return simulator;
    }

    private static byte[] legacyActivationPayload(LocalDate currentDate, LocalDate expiryDate) {
        byte[] payload = new byte[12];
        System.arraycopy(CardId.toBytes("1234"), 0, payload, 0, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, payload, 4, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, payload, 8, 4);
        return payload;
    }

    private static RawCard newProvisionedCard(String memberId) {
        try {
            Simulator simulator = newSimulator();
            KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
            keyGenerator.initialize(512);
            KeyPair masterKeyPair = keyGenerator.generateKeyPair();
            KeyPair cardKeyPair = keyGenerator.generateKeyPair();
            KeyPair adminKeyPair = keyGenerator.generateKeyPair();

            requireSw(transmit(simulator, INS_INITIALIZE_KEY, privateKeyPayload(cardKeyPair)),
                    0x9000, "raw private key initialization");
            requireSw(transmit(simulator, INS_LOAD_MASTER_KEY,
                    publicKeyPayload((RSAPublicKey) masterKeyPair.getPublic())),
                    0x9000, "raw master public key loading");
            requireSw(transmit(simulator, INS_LOAD_CERT,
                    signedCertificate(ProtocolConstants.ROLE_CARD, CardId.toBytes(memberId),
                            (RSAPublicKey) cardKeyPair.getPublic(), masterKeyPair)),
                    0x9000, "raw card certificate loading");

            return new RawCard(simulator, masterKeyPair, adminKeyPair);
        } catch (Exception e) {
            throw new IllegalStateException("Could not provision raw simulator card", e);
        }
    }

    private static int authenticatedActivate(RawCard card, String memberId,
                                             LocalDate currentDate, LocalDate expiryDate) {
        return transmit(card.simulator, INS_ACTIVATE, authenticatedAdminPayloadWithChallenge(card,
                activationOperationData(memberId, currentDate, expiryDate),
                card.adminKeyPair, ProtocolConstants.ROLE_ADMIN_TERMINAL));
    }

    private static int authenticatedActivateWithRole(RawCard card, String memberId,
                                                     LocalDate currentDate, LocalDate expiryDate, byte role) {
        return transmit(card.simulator, INS_ACTIVATE, authenticatedAdminPayloadWithChallenge(card,
                activationOperationData(memberId, currentDate, expiryDate), card.adminKeyPair, role));
    }

    private static int authenticatedBlock(RawCard card, String memberId) {
        return transmit(card.simulator, INS_BLOCK, authenticatedAdminPayloadWithChallenge(card,
                blockOperationData(memberId), card.adminKeyPair, ProtocolConstants.ROLE_ADMIN_TERMINAL));
    }

    private static byte[] authenticatedAdminPayloadWithChallenge(RawCard card, byte[] operationData,
                                                                 KeyPair signingKeyPair, byte role) {
        ApduResponse challenge = transmitResponse(card.simulator, INS_ADMIN_CHALLENGE, null);
        requireSw(challenge.sw, 0x9000, "admin challenge");
        require(challenge.data.length == ProtocolConstants.NONCE_LENGTH,
                "Admin challenge length was " + challenge.data.length);
        return authenticatedAdminPayload(card, operationData, signingKeyPair, role, challenge.data);
    }

    private static byte[] authenticatedAdminPayload(RawCard card, byte[] operationData,
                                                    KeyPair signingKeyPair, byte role, byte[] nonce) {
        try {
            byte[] certificate = signedCertificate(role, new byte[] {0x0A, 0x0B, 0x0C, 0x0E},
                    (RSAPublicKey) signingKeyPair.getPublic(), card.masterKeyPair);
            byte[] signatureInput = new byte[operationData.length + nonce.length];
            System.arraycopy(operationData, 0, signatureInput, 0, operationData.length);
            System.arraycopy(nonce, 0, signatureInput, operationData.length, nonce.length);
            byte[] adminSignature = sign(signingKeyPair, signatureInput);

            byte[] payload = new byte[operationData.length + certificate.length + adminSignature.length];
            System.arraycopy(operationData, 0, payload, 0, operationData.length);
            System.arraycopy(certificate, 0, payload, operationData.length, certificate.length);
            System.arraycopy(adminSignature, 0, payload, operationData.length + certificate.length,
                    adminSignature.length);
            return payload;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build authenticated admin payload", e);
        }
    }

    private static byte[] activationOperationData(String memberId, LocalDate currentDate, LocalDate expiryDate) {
        byte[] data = new byte[ProtocolConstants.ADMIN_ACTIVATE_DATA_LENGTH];
        data[0] = ProtocolConstants.OP_ACTIVATE;
        System.arraycopy(CardId.toBytes(memberId), 0, data, 1, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, data, 5, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, data, 9, 4);
        return data;
    }

    private static byte[] blockOperationData(String memberId) {
        byte[] data = new byte[ProtocolConstants.ADMIN_BLOCK_DATA_LENGTH];
        data[0] = ProtocolConstants.OP_BLOCK;
        System.arraycopy(CardId.toBytes(memberId), 0, data, 1, 4);
        return data;
    }

    private static Tier2Exchange runRawTier2Exchange(RawCard card, String memberId, LocalDate currentDate) {
        try {
            KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
            keyGenerator.initialize(512);
            KeyPair terminalKeyPair = keyGenerator.generateKeyPair();
            byte[] terminalCertificate = signedCertificate(
                    ProtocolConstants.ROLE_CONTROLLED_ACCESS_TERMINAL,
                    new byte[] {0x0A, 0x0B, 0x0C, 0x0D},
                    (RSAPublicKey) terminalKeyPair.getPublic(), card.masterKeyPair);

            byte[] terminalNonce = new byte[ProtocolConstants.NONCE_LENGTH];
            Arrays.fill(terminalNonce, (byte) 0x22);
            ApduResponse step1 = transmitResponse(card.simulator, INS_T2_STEP1, terminalNonce);
            requireSw(step1.sw, 0x9000, "raw Tier 2 step 1");
            require(step1.data.length == ProtocolConstants.TIER2_STEP1_RESPONSE_LENGTH,
                    "Raw Tier 2 step 1 returned " + step1.data.length + " bytes");

            byte[] cardNonce = Arrays.copyOfRange(step1.data, 0, ProtocolConstants.NONCE_LENGTH);
            byte[] cardSignature = Arrays.copyOfRange(step1.data, ProtocolConstants.NONCE_LENGTH,
                    ProtocolConstants.NONCE_LENGTH + ProtocolConstants.SIGNATURE_LENGTH);
            byte[] cardCertificate = Arrays.copyOfRange(step1.data,
                    ProtocolConstants.NONCE_LENGTH + ProtocolConstants.SIGNATURE_LENGTH,
                    ProtocolConstants.TIER2_STEP1_RESPONSE_LENGTH);
            PublicKey cardPublicKey = extractPublicKeyFromCertificate(cardCertificate);

            Signature cardVerifier = Signature.getInstance("SHA1withRSA");
            cardVerifier.initVerify(cardPublicKey);
            cardVerifier.update(terminalNonce);
            require(cardVerifier.verify(cardSignature), "Raw Tier 2 card signature verification failed");

            byte[] date = ApduDateCodec.encode(currentDate);
            byte[] signatureInput = new byte[ProtocolConstants.NONCE_LENGTH + 4];
            System.arraycopy(cardNonce, 0, signatureInput, 0, ProtocolConstants.NONCE_LENGTH);
            System.arraycopy(date, 0, signatureInput, ProtocolConstants.NONCE_LENGTH, 4);
            byte[] terminalSignature = sign(terminalKeyPair, signatureInput);

            byte[] payload = new byte[ProtocolConstants.TIER2_STEP2_PAYLOAD_LENGTH];
            System.arraycopy(terminalSignature, 0, payload, 0, ProtocolConstants.SIGNATURE_LENGTH);
            System.arraycopy(terminalCertificate, 0, payload, ProtocolConstants.SIGNATURE_LENGTH,
                    ProtocolConstants.CARD_CERTIFICATE_LENGTH);
            System.arraycopy(date, 0, payload,
                    ProtocolConstants.SIGNATURE_LENGTH + ProtocolConstants.CARD_CERTIFICATE_LENGTH, 4);

            ApduResponse step2 = transmitResponse(card.simulator, INS_T2_STEP2, payload);
            requireSw(step2.sw, 0x9000, "raw Tier 2 step 2");
            require(step2.data.length == ProtocolConstants.TIER2_RECEIPT_LENGTH,
                    "Raw Tier 2 receipt returned " + step2.data.length + " bytes");

            byte[] cardId = CardId.toBytes(memberId);
            byte[] terminalId = certificateId(terminalCertificate);
            Tier2ReceiptVerifier.Result receipt = Tier2ReceiptVerifier.verify(step2.data, cardPublicKey,
                    cardId, terminalId, date, terminalNonce, cardNonce);
            return new Tier2Exchange(step2.data, receipt, cardPublicKey, cardId, terminalId,
                    date, terminalNonce, cardNonce);
        } catch (Exception e) {
            throw new IllegalStateException("Raw Tier 2 receipt exchange failed", e);
        }
    }

    private static void requireReceiptRejected(byte[] receipt, Tier2Exchange exchange,
                                               byte[] date, byte[] terminalId, String operation) {
        boolean rejected = false;
        try {
            Tier2ReceiptVerifier.verify(receipt, exchange.cardPublicKey, exchange.cardId,
                    terminalId, date, exchange.terminalNonce, exchange.cardNonce);
        } catch (Exception e) {
            rejected = true;
        }
        require(rejected, operation + " was accepted");
    }

    private static byte[] privateKeyPayload(KeyPair cardKeyPair) {
        RSAPrivateKey privateKey = (RSAPrivateKey) cardKeyPair.getPrivate();
        byte[] payload = new byte[128];
        System.arraycopy(toFixedByteArray(privateKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(privateKey.getPrivateExponent(), 64), 0, payload, 64, 64);
        return payload;
    }

    private static byte[] publicKeyPayload(RSAPublicKey publicKey) {
        byte[] payload = new byte[67];
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, payload, 64, 3);
        return payload;
    }

    private static byte[] signedCertificate(byte role, byte[] id, RSAPublicKey publicKey,
                                            KeyPair masterKeyPair) throws Exception {
        byte[] certificate = new byte[ProtocolConstants.CARD_CERTIFICATE_LENGTH];
        certificate[ProtocolConstants.CERT_ROLE_OFFSET] = role;
        System.arraycopy(id, 0, certificate, ProtocolConstants.CERT_ID_OFFSET, 4);
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificate,
                ProtocolConstants.CERT_MODULUS_OFFSET, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificate,
                ProtocolConstants.CERT_EXPONENT_OFFSET, 3);
        System.arraycopy(sign(masterKeyPair, Arrays.copyOf(certificate,
                ProtocolConstants.CERTIFICATE_BODY_LENGTH)), 0, certificate,
                ProtocolConstants.CERT_SIGNATURE_OFFSET, ProtocolConstants.SIGNATURE_LENGTH);
        return certificate;
    }

    private static PublicKey extractPublicKeyFromCertificate(byte[] certificate) throws Exception {
        byte[] modulus = Arrays.copyOfRange(certificate, ProtocolConstants.CERT_MODULUS_OFFSET,
                ProtocolConstants.CERT_EXPONENT_OFFSET);
        byte[] exponent = Arrays.copyOfRange(certificate, ProtocolConstants.CERT_EXPONENT_OFFSET,
                ProtocolConstants.CERT_SIGNATURE_OFFSET);
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent)));
    }

    private static byte[] certificateId(byte[] certificate) {
        return Arrays.copyOfRange(certificate, ProtocolConstants.CERT_ID_OFFSET,
                ProtocolConstants.CERT_MODULUS_OFFSET);
    }

    private static byte[] sign(KeyPair keyPair, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        byte[] signed = signature.sign();
        require(signed.length == ProtocolConstants.SIGNATURE_LENGTH,
                "Expected 64-byte RSA signature, got " + signed.length);
        return signed;
    }

    private static byte[] toFixedByteArray(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] destination = new byte[length];
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, destination, length - copyLength, copyLength);
        return destination;
    }

    private static int transmit(Simulator simulator, byte ins, byte[] data) {
        return transmitResponse(simulator, ins, data).sw;
    }

    private static ApduResponse transmitResponse(Simulator simulator, byte ins, byte[] data) {
        int length = data == null ? 0 : data.length;
        byte[] command = new byte[5 + length];
        command[0] = CLA_PROPRIETARY;
        command[1] = ins;
        command[4] = (byte) length;
        if (data != null) {
            System.arraycopy(data, 0, command, 5, length);
        }
        byte[] response = simulator.transmitCommand(command);
        int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
        return new ApduResponse(Arrays.copyOf(response, response.length - 2), sw);
    }

    private static void requireSw(int actual, int expected, String operation) {
        require(actual == expected, operation + " returned SW=" + String.format("%04X", actual)
                + "; expected " + String.format("%04X", expected));
    }

    private static void requireContains(String actual, String expected, String operation) {
        require(actual != null && actual.contains(expected), operation + " returned: " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class RawCard {
        private final Simulator simulator;
        private final KeyPair masterKeyPair;
        private final KeyPair adminKeyPair;

        private RawCard(Simulator simulator, KeyPair masterKeyPair, KeyPair adminKeyPair) {
            this.simulator = simulator;
            this.masterKeyPair = masterKeyPair;
            this.adminKeyPair = adminKeyPair;
        }
    }

    private static final class Tier2Exchange {
        private final byte[] receiptBytes;
        private final Tier2ReceiptVerifier.Result receipt;
        private final PublicKey cardPublicKey;
        private final byte[] cardId;
        private final byte[] terminalId;
        private final byte[] date;
        private final byte[] terminalNonce;
        private final byte[] cardNonce;

        private Tier2Exchange(byte[] receiptBytes, Tier2ReceiptVerifier.Result receipt,
                              PublicKey cardPublicKey, byte[] cardId, byte[] terminalId,
                              byte[] date, byte[] terminalNonce, byte[] cardNonce) {
            this.receiptBytes = receiptBytes;
            this.receipt = receipt;
            this.cardPublicKey = cardPublicKey;
            this.cardId = cardId;
            this.terminalId = terminalId;
            this.date = date;
            this.terminalNonce = terminalNonce;
            this.cardNonce = cardNonce;
        }
    }

    private static final class ApduResponse {
        private final byte[] data;
        private final int sw;

        private ApduResponse(byte[] data, int sw) {
            this.data = data;
            this.sw = sw;
        }
    }
}
