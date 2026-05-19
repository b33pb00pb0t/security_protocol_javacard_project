package com.sports.recreation.backend;

import applet.MembershipApplet;
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

public class JCardSimGateway {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;
    private static final byte INS_GET_CERT = (byte) 0x60;

    private static final byte[] APPLET_AID = new byte[] {
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x01
    };

    private final Map<String, CardSession> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final KeyPair masterKeyPair;
    private final KeyPair terminalKeyPair;
    private final byte[] terminalCertificate;
    private final byte[] terminalCertificateSignature;

    public JCardSimGateway() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512);
            this.masterKeyPair = keyGen.generateKeyPair();
            this.terminalKeyPair = keyGen.generateKeyPair();
            this.terminalCertificate = buildEntityCertificateData(new byte[] {0x0A, 0x0B, 0x0C, 0x0D},
                    (RSAPublicKey) terminalKeyPair.getPublic());
            this.terminalCertificateSignature = signWithMaster(terminalCertificate);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize simulator issuer keys", e);
        }
    }

    public synchronized boolean hasSession(String memberId) {
        return sessions.containsKey(CardId.normalize(memberId));
    }

    public synchronized boolean isAppletActive(String memberId) {
        CardSession session = sessions.get(CardId.normalize(memberId));
        return session != null && session.appletActive;
    }

    public synchronized void provision(String memberId) {
        String normalized = CardId.normalize(memberId);
        if (sessions.containsKey(normalized)) {
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
            byte[] certC = buildCardCertificate(normalized, (RSAPublicKey) cardKeyPair.getPublic());
            CardSession session = new CardSession(simulator, cardKeyPair, certC);

            requireSuccess(transmit(session, INS_INITIALIZE_KEY, buildPrivateKeyPayload(cardKeyPair)),
                    "Private key initialization");
            requireSuccess(transmit(session, INS_LOAD_MASTER_KEY, buildPublicKeyPayload((RSAPublicKey) masterKeyPair.getPublic())),
                    "Master public key loading");
            requireSuccess(transmit(session, INS_LOAD_CERT, certC), "Card certificate loading");

            sessions.put(normalized, session);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision simulator card " + normalized + ": " + e.getMessage(), e);
        }
    }

    public synchronized void activate(String memberId, LocalDate currentDate) {
        CardSession session = requireSession(memberId);
        byte[] payload = new byte[8];
        System.arraycopy(CardId.toBytes(memberId), 0, payload, 0, 4);
        System.arraycopy(dateBytes(currentDate), 0, payload, 4, 4);
        requireSuccess(transmit(session, INS_ACTIVATE, payload), "Card activation");
        session.appletActive = true;
    }

    public synchronized CardAccessResult blockIfPresent(String memberId) {
        String normalized = CardId.normalize(memberId);
        CardSession session = sessions.get(normalized);
        if (session == null) {
            return CardAccessResult.success("No simulator card session exists for " + normalized);
        }

        ApduResponse response = transmit(session, INS_BLOCK, null);
        if (response.isSuccess()) {
            session.appletActive = false;
            return CardAccessResult.success("Simulator card " + normalized + " moved to BLOCKED state");
        }
        return CardAccessResult.denied("Block APDU failed with SW=" + response.swHex());
    }

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

    public synchronized CardAccessResult checkInTier2(String memberId, LocalDate currentDate) {
        CardSession session = requireSession(memberId);
        byte[] terminalNonce = new byte[16];
        random.nextBytes(terminalNonce);

        ApduResponse step1 = transmit(session, INS_T2_STEP1, terminalNonce);
        if (!step1.isSuccess()) {
            return CardAccessResult.denied("Tier 2 step 1 failed with SW=" + step1.swHex());
        }
        if (step1.data.length != 215) {
            return CardAccessResult.denied("Tier 2 step 1 returned invalid length " + step1.data.length);
        }

        byte[] cardNonce = Arrays.copyOfRange(step1.data, 0, 16);
        byte[] sigma1 = Arrays.copyOfRange(step1.data, 16, 80);
        byte[] certC = Arrays.copyOfRange(step1.data, 80, 215);

        try {
            PublicKey cardPublicKey = verifyAndExtractCardPublicKey(certC);
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(cardPublicKey);
            verifier.update(terminalNonce);
            if (!verifier.verify(sigma1)) {
                return CardAccessResult.denied("Card Tier 2 signature verification failed");
            }

            byte[] date = dateBytes(currentDate);
            byte[] sigma2 = signWithTerminal(cardNonce, date);
            byte[] payload = new byte[203];
            System.arraycopy(sigma2, 0, payload, 0, 64);
            System.arraycopy(terminalCertificate, 0, payload, 64, 71);
            System.arraycopy(terminalCertificateSignature, 0, payload, 135, 64);
            System.arraycopy(date, 0, payload, 199, 4);

            ApduResponse step2 = transmit(session, INS_T2_STEP2, payload);
            if (!step2.isSuccess()) {
                return CardAccessResult.denied("Tier 2 step 2 failed with SW=" + step2.swHex());
            }
            if (step2.data.length != 1) {
                return CardAccessResult.denied("Tier 2 step 2 returned invalid length " + step2.data.length);
            }

            int counter = step2.data[0] & 0xFF;
            return CardAccessResult.success("Tier 2 access granted by simulator card. DailyCounter=" + counter);
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

    private byte[] buildCardCertificate(String memberId, RSAPublicKey cardPublicKey) throws Exception {
        byte[] certificateData = buildEntityCertificateData(CardId.toBytes(memberId), cardPublicKey);
        byte[] certC = new byte[135];
        System.arraycopy(certificateData, 0, certC, 0, 71);
        System.arraycopy(signWithMaster(certificateData), 0, certC, 71, 64);
        return certC;
    }

    private byte[] buildEntityCertificateData(byte[] id, RSAPublicKey publicKey) {
        byte[] certificateData = new byte[71];
        System.arraycopy(id, 0, certificateData, 0, 4);
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificateData, 4, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificateData, 68, 3);
        return certificateData;
    }

    private PublicKey verifyAndExtractCardPublicKey(byte[] certC) throws Exception {
        if (certC.length != 135) {
            throw new SecurityException("Card certificate length must be 135 bytes");
        }
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(masterKeyPair.getPublic());
        verifier.update(certC, 0, 71);
        if (!verifier.verify(certC, 71, 64)) {
            throw new SecurityException("Card certificate signature verification failed");
        }

        byte[] modulus = Arrays.copyOfRange(certC, 4, 68);
        byte[] exponent = Arrays.copyOfRange(certC, 68, 71);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent));
        return KeyFactory.getInstance("RSA").generatePublic(spec);
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

    private byte[] dateBytes(LocalDate date) {
        int year = date.getYear();
        return new byte[] {
                toBcd(year / 100),
                toBcd(year % 100),
                toBcd(date.getMonthValue()),
                toBcd(date.getDayOfMonth())
        };
    }

    private byte toBcd(int value) {
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    private static byte[] toFixedByteArray(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] destination = new byte[length];
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, destination, length - copyLength, copyLength);
        return destination;
    }

    private static final class CardSession {
        private final Simulator simulator;
        private boolean appletActive;
        @SuppressWarnings("unused")
        private final KeyPair cardKeyPair;
        @SuppressWarnings("unused")
        private final byte[] certC;

        private CardSession(Simulator simulator, KeyPair cardKeyPair, byte[] certC) {
            this.simulator = simulator;
            this.appletActive = false;
            this.cardKeyPair = cardKeyPair;
            this.certC = certC;
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

    public static final class CardAccessResult {
        private final boolean success;
        private final String message;

        private CardAccessResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static CardAccessResult success(String message) {
            return new CardAccessResult(true, message);
        }

        public static CardAccessResult denied(String message) {
            return new CardAccessResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
