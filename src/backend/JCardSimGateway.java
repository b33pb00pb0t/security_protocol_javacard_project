package backend;

import applet.MembershipApplet;
import applet.ProtocolConstants;
import com.licel.jcardsim.base.Simulator;
import javacard.framework.AID;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory CardGateway implementation. Each member ID owns an isolated
 * JCardSim applet session for the lifetime of the host process.
 */
public class JCardSimGateway implements CardGateway {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;
    private static final byte INS_ADMIN_CHALLENGE = (byte) 0x30;
    private static final byte INS_GET_CERT = (byte) 0x60;
    private static final byte INS_GET_MEMBER_ID = (byte) 0x61;

    private static final byte[] APPLET_AID = new byte[] {
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x01
    };

    private final Map<String, CardSession> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private CardSession pendingSession;
    private final KeyPair masterKeyPair;
    private final KeyPair adminKeyPair;
    private final KeyPair terminalKeyPair;
    private final byte[] adminCertificate;
    private final byte[] adminCertificateSignature;
    private final byte[] terminalCertificate;
    private final byte[] terminalCertificateSignature;

    public JCardSimGateway() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512);
            this.masterKeyPair = keyGen.generateKeyPair();
            this.adminKeyPair = keyGen.generateKeyPair();
            this.terminalKeyPair = keyGen.generateKeyPair();
            this.adminCertificate = buildEntityCertificateData(
                    ProtocolConstants.ROLE_ADMIN_TERMINAL,
                    new byte[] {0x0A, 0x0B, 0x0C, 0x0E},
                    (RSAPublicKey) adminKeyPair.getPublic());
            this.adminCertificateSignature = signWithMaster(adminCertificate);
            this.terminalCertificate = buildEntityCertificateData(
                    ProtocolConstants.ROLE_CONTROLLED_ACCESS_TERMINAL,
                    new byte[] {0x0A, 0x0B, 0x0C, 0x0D},
                    (RSAPublicKey) terminalKeyPair.getPublic());
            this.terminalCertificateSignature = signWithMaster(terminalCertificate);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize simulator issuer keys", e);
        }
    }

    @Override
    public String getGatewayName() {
        return "SIMULATOR";
    }

    @Override
    public synchronized boolean hasSession(String memberId) {
        return sessions.containsKey(CardId.normalize(memberId));
    }

    @Override
    public synchronized boolean hasInitializedCard() {
        return pendingSession != null;
    }

    @Override
    public synchronized boolean isAppletActive(String memberId) {
        CardSession session = sessions.get(CardId.normalize(memberId));
        return session != null && session.appletActive;
    }

    @Override
    public synchronized void provision() {
        if (pendingSession != null) {
            return;
        }

        try {
            Simulator simulator = new Simulator();
            AID aid = new AID(APPLET_AID, (short) 0, (byte) APPLET_AID.length);
            simulator.installApplet(aid, MembershipApplet.class);
            if (!simulator.selectApplet(aid)) {
                throw new IllegalStateException("Could not select membership applet");
            }

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512);
            KeyPair cardKeyPair = keyGen.generateKeyPair();
            byte[] certC = buildCardCertificate(randomCardIdentity(), (RSAPublicKey) cardKeyPair.getPublic());
            CardSession session = new CardSession(simulator);

            requireSuccess(transmit(session, INS_INITIALIZE_KEY, buildPrivateKeyPayload(cardKeyPair)),
                    "Private key initialization");
            requireSuccess(transmit(session, INS_LOAD_MASTER_KEY, buildPublicKeyPayload((RSAPublicKey) masterKeyPair.getPublic())),
                    "Master public key loading");
            requireSuccess(transmit(session, INS_LOAD_CERT, certC), "Card certificate loading");

            pendingSession = session;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision blank simulator card: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized CardAccessResult resetCard() {
        sessions.clear();
        pendingSession = null;
        return CardAccessResult.success("Simulator card sessions cleared. Initialize a blank card again.");
    }

    @Override
    public synchronized void activate(String memberId, LocalDate currentDate, LocalDate expiryDate) {
        String normalized = CardId.normalize(memberId);
        CardSession session = sessions.get(normalized);
        if (session == null) {
            if (pendingSession == null) {
                throw new IllegalStateException("Initialize a blank simulator card from the Master terminal first");
            }
            session = pendingSession;
            pendingSession = null;
        }
        byte[] payload = buildAuthenticatedAdminPayload(session,
                ProtocolConstants.OP_ACTIVATE,
                activationOperationData(normalized, currentDate, expiryDate));
        requireSuccess(transmit(session, INS_ACTIVATE, payload), "Card activation");
        session.appletActive = true;
        sessions.put(normalized, session);
    }

    @Override
    public synchronized CardAccessResult blockIfPresent(String memberId) {
        String normalized = CardId.normalize(memberId);
        CardSession session = sessions.get(normalized);
        if (session == null) {
            return CardAccessResult.success("No simulator card session exists for " + normalized);
        }

        ApduResponse response = transmit(session, INS_BLOCK,
                buildAuthenticatedAdminPayload(session, ProtocolConstants.OP_BLOCK,
                        blockOperationData(normalized)));
        if (response.isSuccess()) {
            session.appletActive = false;
            return CardAccessResult.success("Simulator card " + normalized + " moved to BLOCKED state");
        }
        return CardAccessResult.denied("Block APDU failed with SW=" + response.swHex());
    }

    @Override
    public synchronized CardAccessResult checkInTier1(String memberId) {
        CardSession session = requireSession(memberId);
        byte[] terminalNonce = new byte[16];
        random.nextBytes(terminalNonce);

        ApduResponse certResponse = transmit(session, INS_GET_CERT, null);
        if (!certResponse.isSuccess()) {
            return CardAccessResult.denied("GET_CERT failed with SW=" + certResponse.swHex());
        }

        try {
            PublicKey cardPublicKey = verifyAndExtractCardPublicKey(certResponse.data);
            verifyAssignedMember(session, memberId);
            ApduResponse signatureResponse = transmit(session, INS_CHECKIN_T1, terminalNonce);
            if (!signatureResponse.isSuccess()) {
                return CardAccessResult.denied("Tier 1 APDU failed with SW=" + signatureResponse.swHex());
            }

            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(cardPublicKey);
            verifier.update(terminalNonce);
            if (!verifier.verify(signatureResponse.data)) {
                return CardAccessResult.denied("Card signature verification failed");
            }

            return CardAccessResult.success("Tier 1 access granted by simulator card");
        } catch (Exception e) {
            return CardAccessResult.denied(e.getMessage());
        }
    }

    @Override
    public synchronized CardAccessResult checkInTier2(String memberId, LocalDate currentDate) {
        CardSession session = requireSession(memberId);
        byte[] terminalNonce = new byte[16];
        random.nextBytes(terminalNonce);

        ApduResponse step1 = transmit(session, INS_T2_STEP1, terminalNonce);
        if (!step1.isSuccess()) {
            return CardAccessResult.denied("Tier 2 step 1 failed with SW=" + step1.swHex());
        }
        if (step1.data.length != ProtocolConstants.TIER2_STEP1_RESPONSE_LENGTH) {
            return CardAccessResult.denied("Tier 2 step 1 returned invalid length " + step1.data.length);
        }

        byte[] cardNonce = Arrays.copyOfRange(step1.data, 0, 16);
        byte[] sigma1 = Arrays.copyOfRange(step1.data, 16, 80);
        byte[] certC = Arrays.copyOfRange(step1.data, 80, ProtocolConstants.TIER2_STEP1_RESPONSE_LENGTH);

        try {
            PublicKey cardPublicKey = verifyAndExtractCardPublicKey(certC);
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(cardPublicKey);
            verifier.update(terminalNonce);
            if (!verifier.verify(sigma1)) {
                return CardAccessResult.denied("Card Tier 2 signature verification failed");
            }
            verifyAssignedMember(session, memberId);

            byte[] date = ApduDateCodec.encode(currentDate);
            byte[] sigma2 = signWithTerminal(cardNonce, date);
            byte[] payload = new byte[ProtocolConstants.TIER2_STEP2_PAYLOAD_LENGTH];
            System.arraycopy(sigma2, 0, payload, 0, 64);
            System.arraycopy(terminalCertificate, 0, payload, 64, ProtocolConstants.CERTIFICATE_BODY_LENGTH);
            System.arraycopy(terminalCertificateSignature, 0, payload,
                    64 + ProtocolConstants.CERTIFICATE_BODY_LENGTH, ProtocolConstants.SIGNATURE_LENGTH);
            System.arraycopy(date, 0, payload,
                    64 + ProtocolConstants.CERTIFICATE_BODY_LENGTH + ProtocolConstants.SIGNATURE_LENGTH, 4);

            ApduResponse step2 = transmit(session, INS_T2_STEP2, payload);
            if (!step2.isSuccess()) {
                return CardAccessResult.denied("Tier 2 step 2 failed with SW=" + step2.swHex());
            }
            if (step2.data.length != ProtocolConstants.TIER2_RECEIPT_LENGTH) {
                return CardAccessResult.denied("Tier 2 step 2 returned invalid length " + step2.data.length);
            }

            Tier2ReceiptVerifier.Result receipt = Tier2ReceiptVerifier.verify(step2.data, cardPublicKey,
                    CardId.toBytes(memberId), extractCertificateId(terminalCertificate), date,
                    terminalNonce, cardNonce);
            return CardAccessResult.success("Tier 2 access granted by simulator card. DailyCounter="
                    + receipt.getDailyCounter() + " TxCounter=" + receipt.getTransactionCounter()
                    + " ReceiptVerified=true Receipt=" + toHex(step2.data));
        } catch (Exception e) {
            return CardAccessResult.denied(e.getMessage());
        }
    }

    private CardSession requireSession(String memberId) {
        String normalized = CardId.normalize(memberId);
        CardSession session = sessions.get(normalized);
        if (session == null) {
            throw new IllegalStateException("Initialize simulator card " + normalized + " first");
        }
        return session;
    }

    private byte[] buildPrivateKeyPayload(KeyPair cardKeyPair) {
        RSAPrivateKey privateKey = (RSAPrivateKey) cardKeyPair.getPrivate();
        byte[] payload = new byte[128];
        System.arraycopy(toFixedByteArray(privateKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(privateKey.getPrivateExponent(), 64), 0, payload, 64, 64);
        return payload;
    }

    private byte[] buildPublicKeyPayload(RSAPublicKey publicKey) {
        byte[] payload = new byte[67];
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, payload, 64, 3);
        return payload;
    }

    private byte[] activationOperationData(String memberId, LocalDate currentDate, LocalDate expiryDate) {
        byte[] data = new byte[ProtocolConstants.ADMIN_ACTIVATE_DATA_LENGTH];
        data[0] = ProtocolConstants.OP_ACTIVATE;
        System.arraycopy(CardId.toBytes(memberId), 0, data, 1, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, data, 5, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, data, 9, 4);
        return data;
    }

    private byte[] blockOperationData(String memberId) {
        byte[] data = new byte[ProtocolConstants.ADMIN_BLOCK_DATA_LENGTH];
        data[0] = ProtocolConstants.OP_BLOCK;
        System.arraycopy(CardId.toBytes(memberId), 0, data, 1, 4);
        return data;
    }

    private byte[] buildAuthenticatedAdminPayload(CardSession session, byte operation, byte[] operationData) {
        ApduResponse challenge = transmit(session, INS_ADMIN_CHALLENGE, null);
        requireSuccess(challenge, "Admin challenge");
        if (challenge.data.length != ProtocolConstants.NONCE_LENGTH) {
            throw new IllegalStateException("Admin challenge returned " + challenge.data.length
                    + " bytes; expected " + ProtocolConstants.NONCE_LENGTH);
        }
        int expectedLength = operation == ProtocolConstants.OP_ACTIVATE
                ? ProtocolConstants.ADMIN_ACTIVATE_PAYLOAD_LENGTH
                : ProtocolConstants.ADMIN_BLOCK_PAYLOAD_LENGTH;
        byte[] payload = new byte[expectedLength];
        System.arraycopy(operationData, 0, payload, 0, operationData.length);
        System.arraycopy(adminCertificate, 0, payload, operationData.length,
                ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        System.arraycopy(adminCertificateSignature, 0, payload,
                operationData.length + ProtocolConstants.CERTIFICATE_BODY_LENGTH,
                ProtocolConstants.SIGNATURE_LENGTH);
        System.arraycopy(signAdminOperation(operationData, challenge.data), 0, payload,
                operationData.length + ProtocolConstants.CERTIFICATE_BODY_LENGTH
                        + ProtocolConstants.SIGNATURE_LENGTH,
                ProtocolConstants.SIGNATURE_LENGTH);
        return payload;
    }

    private byte[] buildCardCertificate(byte[] cardIdentity, RSAPublicKey cardPublicKey) throws Exception {
        byte[] certificateData = buildEntityCertificateData(
                ProtocolConstants.ROLE_CARD, cardIdentity, cardPublicKey);
        byte[] certC = new byte[ProtocolConstants.CARD_CERTIFICATE_LENGTH];
        System.arraycopy(certificateData, 0, certC, 0, ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        System.arraycopy(signWithMaster(certificateData), 0, certC,
                ProtocolConstants.CERT_SIGNATURE_OFFSET, ProtocolConstants.SIGNATURE_LENGTH);
        return certC;
    }

    private byte[] randomCardIdentity() {
        byte[] identity = new byte[4];
        do {
            random.nextBytes(identity);
        } while (identity[0] == 0 && identity[1] == 0 && identity[2] == 0 && identity[3] == 0);
        return identity;
    }

    private byte[] buildEntityCertificateData(byte role, byte[] id, RSAPublicKey publicKey) {
        byte[] certificateData = new byte[ProtocolConstants.CERTIFICATE_BODY_LENGTH];
        certificateData[ProtocolConstants.CERT_ROLE_OFFSET] = role;
        System.arraycopy(id, 0, certificateData, ProtocolConstants.CERT_ID_OFFSET, 4);
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificateData,
                ProtocolConstants.CERT_MODULUS_OFFSET, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificateData,
                ProtocolConstants.CERT_EXPONENT_OFFSET, 3);
        return certificateData;
    }

    private PublicKey verifyAndExtractCardPublicKey(byte[] certC) throws Exception {
        if (certC.length != ProtocolConstants.CARD_CERTIFICATE_LENGTH) {
            throw new SecurityException("Card certificate length must be "
                    + ProtocolConstants.CARD_CERTIFICATE_LENGTH + " bytes");
        }
        if (certC[ProtocolConstants.CERT_ROLE_OFFSET] != ProtocolConstants.ROLE_CARD) {
            throw new SecurityException("Certificate role is not ROLE_CARD");
        }
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(masterKeyPair.getPublic());
        verifier.update(certC, 0, ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        if (!verifier.verify(certC, ProtocolConstants.CERT_SIGNATURE_OFFSET,
                ProtocolConstants.SIGNATURE_LENGTH)) {
            throw new SecurityException("Card certificate signature verification failed");
        }

        byte[] modulus = Arrays.copyOfRange(certC, ProtocolConstants.CERT_MODULUS_OFFSET,
                ProtocolConstants.CERT_EXPONENT_OFFSET);
        byte[] exponent = Arrays.copyOfRange(certC, ProtocolConstants.CERT_EXPONENT_OFFSET,
                ProtocolConstants.CERT_SIGNATURE_OFFSET);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent));
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private byte[] extractCertificateId(byte[] certificate) {
        return Arrays.copyOfRange(certificate, ProtocolConstants.CERT_ID_OFFSET,
                ProtocolConstants.CERT_MODULUS_OFFSET);
    }

    private void verifyAssignedMember(CardSession session, String expectedMemberId) {
        ApduResponse response = transmit(session, INS_GET_MEMBER_ID, null);
        requireSuccess(response, "Assigned member read");
        if (response.data.length != 4) {
            throw new SecurityException("Assigned member response length was " + response.data.length);
        }
        if (!Arrays.equals(response.data, CardId.toBytes(expectedMemberId))) {
            throw new SecurityException("Card is assigned to " + toHex(response.data)
                    + ", not requested member " + CardId.normalize(expectedMemberId));
        }
    }

    private byte[] signWithMaster(byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(masterKeyPair.getPrivate());
        signature.update(data);
        byte[] signed = signature.sign();
        if (signed.length != 64) {
            throw new IllegalStateException("Expected 64-byte RSA signature");
        }
        return signed;
    }

    private byte[] signWithTerminal(byte[] cardNonce, byte[] currentDate) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(terminalKeyPair.getPrivate());
        signature.update(cardNonce);
        signature.update(currentDate);
        byte[] signed = signature.sign();
        if (signed.length != 64) {
            throw new IllegalStateException("Expected 64-byte terminal signature");
        }
        return signed;
    }

    private byte[] signAdminOperation(byte[] operationData, byte[] nonce) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(adminKeyPair.getPrivate());
            signature.update(operationData);
            signature.update(nonce);
            byte[] signed = signature.sign();
            if (signed.length != 64) {
                throw new IllegalStateException("Expected 64-byte admin signature");
            }
            return signed;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign admin operation", e);
        }
    }

    private ApduResponse transmit(CardSession session, byte ins, byte[] data) {
        int lc = data == null ? 0 : data.length;
        byte[] command = new byte[5 + lc];
        command[0] = CLA_PROPRIETARY;
        command[1] = ins;
        command[2] = 0x00;
        command[3] = 0x00;
        command[4] = (byte) lc;
        if (data != null) {
            System.arraycopy(data, 0, command, 5, data.length);
        }

        byte[] response = session.simulator.transmitCommand(command);
        if (response.length < 2) {
            throw new IllegalStateException("Malformed APDU response");
        }
        int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
        byte[] responseData = Arrays.copyOf(response, response.length - 2);
        return new ApduResponse(responseData, sw);
    }

    private void requireSuccess(ApduResponse response, String operation) {
        if (!response.isSuccess()) {
            throw new IllegalStateException(operation + " failed with SW=" + response.swHex());
        }
    }

    private static byte[] toFixedByteArray(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] destination = new byte[length];
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, destination, length - copyLength, copyLength);
        return destination;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format("%02X", current & 0xFF));
        }
        return value.toString();
    }

    private static final class CardSession {
        private final Simulator simulator;
        private boolean appletActive;

        private CardSession(Simulator simulator) {
            this.simulator = simulator;
            this.appletActive = false;
        }
    }

    private static final class ApduResponse {
        private final byte[] data;
        private final int sw;

        private ApduResponse(byte[] data, int sw) {
            this.data = data;
            this.sw = sw;
        }

        private boolean isSuccess() {
            return sw == 0x9000;
        }

        private String swHex() {
            return String.format("%04X", sw);
        }
    }

    public static final class CardAccessResult extends CardGateway.CardAccessResult {
        private CardAccessResult(boolean success, String message) {
            super(success, message);
        }

        public static CardAccessResult success(String message) {
            return new CardAccessResult(true, message);
        }

        public static CardAccessResult denied(String message) {
            return new CardAccessResult(false, message);
        }
    }

}
