package backend;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HardwareCardGateway implements CardGateway {
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

    private static final byte[] DEFAULT_APPLET_AID = new byte[] {
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x02, (byte) 0x03, (byte) 0x01
    };

    private static final Path HARDWARE_KEY_DIRECTORY = Paths.get("hardware_keys");
    private static final long DEFAULT_CARD_WAIT_MILLIS = 10000L;

    private final SecureRandom random = new SecureRandom();
    private final String preferredReaderName;
    private final byte[] appletAid;
    private final KeyPair masterKeyPair;
    private final KeyPair terminalKeyPair;
    private final byte[] terminalCertificate;
    private final byte[] terminalCertificateSignature;
    private final String masterKeySource;
    private final String terminalKeySource;
    private final String terminalCertificateSource;

    private CardTerminal terminal;
    private Card card;
    private CardChannel channel;
    private String boundMemberId;
    private Boolean appletActive;
    private boolean provisionedInThisSession;
    private boolean tier2Debug;

    public HardwareCardGateway() {
        this(configuredReaderName(), configuredAppletAid());
    }

    public HardwareCardGateway(String preferredReaderName) {
        this(preferredReaderName, configuredAppletAid());
    }

    HardwareCardGateway(String preferredReaderName, byte[] appletAid) {
        this.preferredReaderName = blankToNull(preferredReaderName);
        this.appletAid = Arrays.copyOf(appletAid, appletAid.length);
        try {
            boolean masterKeyExists = keyPairExists("master");
            boolean terminalKeyExists = keyPairExists("terminal");
            this.masterKeyPair = loadOrCreateKeyPair("master");
            this.terminalKeyPair = loadOrCreateKeyPair("terminal");
            this.masterKeySource = keySource("master", masterKeyExists);
            this.terminalKeySource = keySource("terminal", terminalKeyExists);
            Files.write(Paths.get("master_public.key"), masterKeyPair.getPublic().getEncoded());
            this.terminalCertificate = buildEntityCertificateData(
                    new byte[] {0x0A, 0x0B, 0x0C, 0x0D},
                    (RSAPublicKey) terminalKeyPair.getPublic());
            this.terminalCertificateSignature = signWithMaster(terminalCertificate);
            this.terminalCertificateSource = "generated from " + terminalKeySource
                    + " and signed with " + masterKeySource;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize hardware gateway credentials", e);
        }
    }

    @Override
    public String getGatewayName() {
        return "HARDWARE";
    }

    public void setTier2Debug(boolean tier2Debug) {
        this.tier2Debug = tier2Debug;
    }

    public static List<String> listAvailableReaders() {
        try {
            List<String> names = new ArrayList<>();
            for (CardTerminal current : TerminalFactory.getDefault().terminals().list()) {
                names.add(current.getName());
            }
            return names;
        } catch (CardException e) {
            throw new IllegalStateException("Could not list PC/SC card readers: " + e.getMessage(), e);
        }
    }

    public synchronized void connect() {
        if (channel != null) {
            return;
        }

        try {
            List<CardTerminal> readers = TerminalFactory.getDefault().terminals().list();
            if (readers.isEmpty()) {
                throw new IllegalStateException("No PC/SC smartcard readers were found");
            }

            terminal = chooseReader(readers);
            if (!terminal.isCardPresent() && !terminal.waitForCardPresent(cardWaitMillis())) {
                throw new IllegalStateException("No card was inserted in reader '" + terminal.getName()
                        + "' within " + cardWaitMillis() + " ms");
            }

            card = terminal.connect("*");
            channel = card.getBasicChannel();
            selectApplet();
        } catch (CardException e) {
            disconnect(false);
            throw new IllegalStateException("Could not connect to physical card: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            disconnect(false);
            throw e;
        }
    }

    public synchronized void selectApplet() {
        if (channel == null) {
            throw new IllegalStateException("Connect to a physical card before selecting the applet");
        }
        try {
            ResponseAPDU response = channel.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, appletAid));
            if (response.getSW() != 0x9000) {
                throw new IllegalStateException("Applet SELECT failed with SW="
                        + String.format("%04X", response.getSW()) + " for AID " + getAppletAidHex()
                        + ". First check AID in build.xml, BaseTerminal and GP output.");
            }
        } catch (CardException e) {
            throw new IllegalStateException("Applet SELECT failed for AID " + getAppletAidHex()
                    + ": " + e.getMessage(), e);
        }
    }

    public synchronized void disconnect(boolean reset) {
        try {
            if (card != null) {
                card.disconnect(reset);
            }
        } catch (CardException ignored) {
            // The card may already have been removed.
        } finally {
            terminal = null;
            card = null;
            channel = null;
            boundMemberId = null;
            appletActive = null;
            provisionedInThisSession = false;
        }
    }

    public synchronized String getReaderName() {
        return terminal == null ? "Not connected" : terminal.getName();
    }

    public synchronized String getAtrHex() {
        return card == null ? "Not connected" : toHex(card.getATR().getBytes());
    }

    public String getAppletAidHex() {
        return toHex(appletAid);
    }

    public void printAidDebug() {
        System.out.println("HardwareCardGateway AID: " + getAppletAidHex());
        System.out.println("Expected BaseTerminal AID: A0000001020301");
        System.out.println("Expected build.xml applet AID: A0000001020301");
    }

    @Override
    public synchronized boolean hasSession(String memberId) {
        ensureMemberCompatible(memberId);
        return true;
    }

    @Override
    public synchronized boolean isAppletActive(String memberId) {
        ensureMemberCompatible(memberId);
        // The applet has no GET_STATE command. Unknown state is treated as active so an
        // already-provisioned card can be used after restarting the host application.
        return appletActive == null || appletActive.booleanValue();
    }

    @Override
    public synchronized void provision(String memberId) {
        String normalized = ensureMemberCompatible(memberId);
        if (provisionedInThisSession && normalized.equals(boundMemberId)) {
            return;
        }
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512);
            KeyPair cardKeyPair = keyGen.generateKeyPair();
            byte[] certC = buildCardCertificate(normalized, (RSAPublicKey) cardKeyPair.getPublic());

            requireSuccess(transmit(INS_INITIALIZE_KEY, buildPrivateKeyPayload(cardKeyPair)),
                    "Private key initialization");
            requireSuccess(transmit(INS_LOAD_MASTER_KEY,
                    buildPublicKeyPayload((RSAPublicKey) masterKeyPair.getPublic())), "Master public key loading");
            requireSuccess(transmit(INS_LOAD_CERT, certC), "Card certificate loading");

            boundMemberId = normalized;
            appletActive = Boolean.FALSE;
            provisionedInThisSession = true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision physical card " + normalized
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void activate(String memberId, LocalDate currentDate, LocalDate expiryDate) {
        String normalized = ensureMemberCompatible(memberId);
        if (boundMemberId == null) {
            ApduResponse certificateResponse = transmit(INS_GET_CERT, null);
            requireSuccess(certificateResponse, "GET_CERT before activation");
            try {
                verifyAndExtractCardPublicKey(certificateResponse.data);
                verifyCardMember(certificateResponse.data, normalized);
            } catch (Exception e) {
                throw new IllegalStateException("Could not verify card before activation: " + e.getMessage(), e);
            }
            boundMemberId = normalized;
        }
        byte[] payload = new byte[12];
        System.arraycopy(CardId.toBytes(normalized), 0, payload, 0, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, payload, 4, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, payload, 8, 4);
        ApduResponse activationResponse = transmit(INS_ACTIVATE, payload);
        if (!activationResponse.isSuccess()) {
            // The applet rejects activation while already ACTIVE. Prove that state with
            // a non-mutating Tier 1 challenge so backend reactivation remains idempotent.
            if (activationResponse.sw == 0x6985 && checkInTier1(normalized).isSuccess()) {
                appletActive = Boolean.TRUE;
                return;
            }
            requireSuccess(activationResponse, "Card activation");
        }
        boundMemberId = normalized;
        appletActive = Boolean.TRUE;
    }

    @Override
    public synchronized CardAccessResult blockIfPresent(String memberId) {
        try {
            String normalized = ensureMemberCompatible(memberId);
            ApduResponse certificateResponse = transmit(INS_GET_CERT, null);
            requireSuccess(certificateResponse, "GET_CERT before block");
            verifyAndExtractCardPublicKey(certificateResponse.data);
            verifyCardMember(certificateResponse.data, normalized);
            ApduResponse response = transmit(INS_BLOCK, null);
            if (!response.isSuccess()) {
                return CardAccessResult.denied("Block APDU failed with SW=" + response.swHex());
            }
            boundMemberId = normalized;
            appletActive = Boolean.FALSE;
            return CardAccessResult.success("Physical card " + normalized + " moved to BLOCKED state");
        } catch (Exception e) {
            return CardAccessResult.denied(e.getMessage());
        }
    }

    @Override
    public synchronized CardAccessResult checkInTier1(String memberId) {
        try {
            ensureMemberCompatible(memberId);
            byte[] terminalNonce = new byte[16];
            random.nextBytes(terminalNonce);

            ApduResponse certResponse = transmit(INS_GET_CERT, null);
            requireSuccess(certResponse, "GET_CERT");
            PublicKey cardPublicKey = verifyAndExtractCardPublicKey(certResponse.data);
            verifyCardMember(certResponse.data, CardId.normalize(memberId));

            ApduResponse signatureResponse = transmit(INS_CHECKIN_T1, terminalNonce);
            requireSuccess(signatureResponse, "Tier 1 APDU");
            if (signatureResponse.data.length != 64) {
                return CardAccessResult.denied("Tier 1 returned " + signatureResponse.data.length
                        + " bytes; expected 64");
            }

            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(cardPublicKey);
            verifier.update(terminalNonce);
            if (!verifier.verify(signatureResponse.data)) {
                return CardAccessResult.denied("Card signature verification failed");
            }

            appletActive = Boolean.TRUE;
            return CardAccessResult.success("Tier 1 access granted by physical card");
        } catch (Exception e) {
            return CardAccessResult.denied(e.getMessage());
        }
    }

    @Override
    public synchronized CardAccessResult checkInTier2(String memberId, LocalDate currentDate) {
        try {
            String normalizedMemberId = ensureMemberCompatible(memberId);
            debugTier2("Selected AID: " + getAppletAidHex());
            debugTier2("Reader: " + getReaderName());
            debugTier2("ATR: " + getAtrHex());
            debugTier2("Master public key source: " + masterKeySource);
            debugTier2("Terminal key source: " + terminalKeySource);
            debugTier2("Terminal certificate source: " + terminalCertificateSource);

            byte[] terminalNonce = new byte[16];
            random.nextBytes(terminalNonce);

            debugTier2("Step 1 request: INS=0x" + String.format("%02X", INS_T2_STEP1 & 0xFF)
                    + ", payloadLength=" + terminalNonce.length);
            ApduResponse step1 = transmit(INS_T2_STEP1, terminalNonce);
            debugTier2("Step 1 response: SW=" + step1.swHex() + ", dataLength=" + step1.data.length);
            requireSuccess(step1, "Tier 2 step 1");
            if (step1.data.length != 215) {
                return CardAccessResult.denied("Tier 2 step 1 returned " + step1.data.length
                        + " bytes; expected 215");
            }

            byte[] cardNonce = Arrays.copyOfRange(step1.data, 0, 16);
            byte[] cardSignature = Arrays.copyOfRange(step1.data, 16, 80);
            byte[] certC = Arrays.copyOfRange(step1.data, 80, 215);
            debugTier2("Step 1 parsed: NC=" + cardNonce.length + ", cardSignature="
                    + cardSignature.length + ", cardCertificate=" + certC.length);
            debugTier2("Card certificate: ID=" + toHex(Arrays.copyOfRange(certC, 0, 4))
                    + ", length=" + certC.length);

            boolean masterSignatureValid = verifyCardCertificateSignature(certC);
            debugTier2("Card certificate master signature verification: " + masterSignatureValid);
            if (!masterSignatureValid) {
                throw new SecurityException("Card certificate signature verification failed");
            }
            PublicKey cardPublicKey = extractCardPublicKey(certC);
            verifyCardMember(certC, normalizedMemberId);

            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(cardPublicKey);
            verifier.update(terminalNonce);
            boolean cardSignatureValid = verifier.verify(cardSignature);
            debugTier2("Card signature verification: " + cardSignatureValid);
            if (!cardSignatureValid) {
                return CardAccessResult.denied("Card Tier 2 signature verification failed");
            }

            byte[] date = ApduDateCodec.encode(currentDate);
            byte[] terminalSignature = signWithTerminal(cardNonce, date);
            byte[] payload = new byte[203];
            System.arraycopy(terminalSignature, 0, payload, 0, 64);
            System.arraycopy(terminalCertificate, 0, payload, 64, 71);
            System.arraycopy(terminalCertificateSignature, 0, payload, 135, 64);
            System.arraycopy(date, 0, payload, 199, 4);

            debugTier2("Step 2 request: INS=0x" + String.format("%02X", INS_T2_STEP2 & 0xFF)
                    + ", terminalSignature=" + terminalSignature.length
                    + ", terminalCertificate=" + terminalCertificate.length
                    + ", terminalMasterSignature=" + terminalCertificateSignature.length);
            debugTier2("Step 2 date: " + toHex(date));
            debugTier2("Step 2 payloadLength=" + payload.length + " (expected 203)");
            debugTier2("Step 2 payload hex: " + hexPrefixSuffix(payload, 24));
            ApduResponse step2 = transmit(INS_T2_STEP2, payload);
            debugTier2("Step 2 response: SW=" + step2.swHex() + ", dataLength=" + step2.data.length);
            if (step2.sw == 0x6F00) {
                debugTier2("Tier 2 step 2 reached the applet but failed internally. Likely causes: "
                        + "wrong master key on card, terminal certificate signed with different key, "
                        + "old installed applet, or physical APDU receive issue.");
            }
            requireSuccess(step2, "Tier 2 step 2");
            if (step2.data.length != 1) {
                return CardAccessResult.denied("Tier 2 step 2 returned " + step2.data.length
                        + " bytes; expected 1");
            }

            appletActive = Boolean.TRUE;
            return CardAccessResult.success("Tier 2 access granted by physical card. DailyCounter="
                    + (step2.data[0] & 0xFF));
        } catch (Exception e) {
            return CardAccessResult.denied(e.getMessage());
        }
    }

    private String ensureMemberCompatible(String memberId) {
        String normalized = CardId.normalize(memberId);
        connect();
        if (boundMemberId != null && !boundMemberId.equals(normalized)) {
            throw new IllegalStateException("Inserted card is bound to " + boundMemberId
                    + ", not requested member " + normalized);
        }
        return normalized;
    }

    private ApduResponse transmit(byte ins, byte[] data) {
        connect();
        int length = data == null ? 0 : data.length;
        if (length > 255) {
            throw new IllegalArgumentException("APDU payload exceeds short APDU limit: " + length);
        }

        CommandAPDU command = length == 0
                ? new CommandAPDU(CLA_PROPRIETARY, ins, 0x00, 0x00)
                : new CommandAPDU(CLA_PROPRIETARY, ins, 0x00, 0x00, data);
        try {
            ResponseAPDU response = channel.transmit(command);
            return new ApduResponse(response.getData(), response.getSW());
        } catch (CardException e) {
            disconnect(false);
            throw new IllegalStateException("Physical APDU " + String.format("%02X", ins & 0xFF)
                    + " failed: " + e.getMessage(), e);
        }
    }

    private CardTerminal chooseReader(List<CardTerminal> readers) {
        if (preferredReaderName == null) {
            return readers.get(0);
        }
        for (CardTerminal reader : readers) {
            if (reader.getName().equalsIgnoreCase(preferredReaderName)
                    || reader.getName().toLowerCase().contains(preferredReaderName.toLowerCase())) {
                return reader;
            }
        }
        throw new IllegalStateException("Requested reader '" + preferredReaderName
                + "' was not found. Available readers: " + listAvailableReaders());
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
        if (!verifyCardCertificateSignature(certC)) {
            throw new SecurityException("Card certificate signature verification failed");
        }
        return extractCardPublicKey(certC);
    }

    private boolean verifyCardCertificateSignature(byte[] certC) throws Exception {
        if (certC.length != 135) {
            return false;
        }
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(masterKeyPair.getPublic());
        verifier.update(certC, 0, 71);
        return verifier.verify(certC, 71, 64);
    }

    private PublicKey extractCardPublicKey(byte[] certC) throws Exception {
        byte[] modulus = Arrays.copyOfRange(certC, 4, 68);
        byte[] exponent = Arrays.copyOfRange(certC, 68, 71);
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent)));
    }

    private void verifyCardMember(byte[] certC, String expectedMemberId) {
        String certificateMemberId = toHex(Arrays.copyOfRange(certC, 0, 4));
        if (!certificateMemberId.equals(CardId.normalize(expectedMemberId))) {
            throw new SecurityException("Inserted card certificate belongs to " + certificateMemberId
                    + ", not requested member " + CardId.normalize(expectedMemberId));
        }
    }

    private byte[] signWithMaster(byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(masterKeyPair.getPrivate());
        signature.update(data);
        return requireSignatureLength(signature.sign());
    }

    private byte[] signWithTerminal(byte[] cardNonce, byte[] currentDate) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(terminalKeyPair.getPrivate());
        signature.update(cardNonce);
        signature.update(currentDate);
        return requireSignatureLength(signature.sign());
    }

    private static byte[] requireSignatureLength(byte[] signature) {
        if (signature.length != 64) {
            throw new IllegalStateException("Expected 64-byte RSA signature, got " + signature.length);
        }
        return signature;
    }

    private static KeyPair loadOrCreateKeyPair(String name) throws Exception {
        Files.createDirectories(HARDWARE_KEY_DIRECTORY);
        Path privatePath = HARDWARE_KEY_DIRECTORY.resolve(name + "_private.pkcs8");
        Path publicPath = HARDWARE_KEY_DIRECTORY.resolve(name + "_public.x509");
        KeyFactory factory = KeyFactory.getInstance("RSA");

        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            return new KeyPair(publicKey, privateKey);
        }
        if (Files.exists(privatePath) || Files.exists(publicPath)) {
            throw new IllegalStateException("Incomplete hardware key pair for " + name + " in "
                    + HARDWARE_KEY_DIRECTORY.toAbsolutePath());
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(512);
        KeyPair keyPair = generator.generateKeyPair();
        Files.write(privatePath, keyPair.getPrivate().getEncoded());
        Files.write(publicPath, keyPair.getPublic().getEncoded());
        return keyPair;
    }

    private static boolean keyPairExists(String name) {
        return Files.exists(HARDWARE_KEY_DIRECTORY.resolve(name + "_private.pkcs8"))
                && Files.exists(HARDWARE_KEY_DIRECTORY.resolve(name + "_public.x509"));
    }

    private static String keySource(String name, boolean loadedFromFiles) {
        return loadedFromFiles
                ? "loaded from hardware_keys/" + name + "_private.pkcs8 and " + name + "_public.x509"
                : "generated and stored in hardware_keys/ for " + name;
    }

    private static void requireSuccess(ApduResponse response, String operation) {
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

    private static String configuredReaderName() {
        String property = System.getProperty("card.reader");
        return blankToNull(property) == null ? System.getenv("CARD_READER") : property;
    }

    private static byte[] configuredAppletAid() {
        String configured = System.getProperty("card.applet.aid");
        if (blankToNull(configured) == null) {
            configured = System.getenv("CARD_APPLET_AID");
        }
        return blankToNull(configured) == null ? DEFAULT_APPLET_AID : parseHex(configured);
    }

    private static long cardWaitMillis() {
        String configured = System.getProperty("card.wait.ms");
        return blankToNull(configured) == null ? DEFAULT_CARD_WAIT_MILLIS : Long.parseLong(configured);
    }

    private static byte[] parseHex(String value) {
        String normalized = value.replace(" ", "").replace(":", "").toUpperCase();
        if (normalized.length() < 10 || normalized.length() > 32 || normalized.length() % 2 != 0
                || !normalized.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException("Applet AID must be 5-16 bytes of hexadecimal");
        }
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format("%02X", current & 0xFF));
        }
        return value.toString();
    }

    private static String hexPrefixSuffix(byte[] bytes, int partLength) {
        if (bytes.length <= partLength * 2) {
            return toHex(bytes);
        }
        return toHex(Arrays.copyOfRange(bytes, 0, partLength))
                + "..."
                + toHex(Arrays.copyOfRange(bytes, bytes.length - partLength, bytes.length));
    }

    private void debugTier2(String message) {
        if (tier2Debug) {
            System.out.println("[Tier2 Debug] " + message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
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
}
