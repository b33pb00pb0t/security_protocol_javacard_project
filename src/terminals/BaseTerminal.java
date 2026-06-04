package terminals;

import javax.smartcardio.*;
import java.util.List;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.math.BigInteger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Shared PC/SC support for the standalone terminal command-line demos.
 * The GUI hardware path uses HardwareCardGateway instead.
 */
public abstract class BaseTerminal {
    protected Card card;
    protected CardChannel channel;
    protected PublicKey masterPublicKey;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    protected static final byte[] APPLET_AID = configuredAppletAid();

    private static final byte INS_GET_CERT = (byte) 0x60;

    public void loadMasterPublicKey(String filePath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(filePath));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA", "BC");
        this.masterPublicKey = kf.generatePublic(spec);
        System.out.println("[Terminal] Master Public Key loaded correctly.");
    }

    /**
     * Verifies the Card Certificate using the Master Public Key.
     * Returns the verified Card ID (4 bytes).
     */
    protected byte[] verifyAndGetIdFromCert() throws Exception {
        return verifyAndGetIdFromCert(getCardCertificate());
    }

    protected byte[] verifyAndGetIdFromCert(byte[] fullResponse) throws Exception {
        if (masterPublicKey == null) {
            throw new SecurityException("Master Public Key not loaded. Cannot verify card.");
        }
        // Cert_C: ID_C(4) || modulus(64) || exponent(3) || masterSignature(64).
        if (fullResponse.length != 135) {
            throw new SecurityException("Invalid certificate length received from card: "
                    + fullResponse.length + "; expected 135");
        }

        ByteBuffer buffer = ByteBuffer.wrap(fullResponse);
        byte[] idC = new byte[4];
        byte[] modulusC = new byte[64];
        byte[] exponentC = new byte[3];
        byte[] signatureMT = new byte[64];

        buffer.get(idC);
        buffer.get(modulusC);
        buffer.get(exponentC);
        buffer.get(signatureMT);

        byte[] signedData = new byte[4 + 64 + 3];
        System.arraycopy(fullResponse, 0, signedData, 0, signedData.length);

        Signature verifier = Signature.getInstance("SHA1withRSA", "BC");
        verifier.initVerify(masterPublicKey);
        verifier.update(signedData);

        if (!verifier.verify(signatureMT)) {
            throw new SecurityException("CRITICAL: Card certificate signature verification failed!");
        }

        System.out.println("[Terminal] Card Certificate verified successfully via PK_M.");
        return idC;
    }

    protected byte[] getCardCertificate() throws CardException {
        ResponseAPDU response = send(new CommandAPDU(0xB0, INS_GET_CERT, 0x00, 0x00, 135));
        if (response.getSW() != 0x9000) {
            throw new CardException("Failed to retrieve certificate. SW: "
                    + String.format("%04X", response.getSW()));
        }
        if (response.getData().length != 135) {
            throw new CardException("Card certificate response length was " + response.getData().length
                    + "; expected 135");
        }
        return response.getData();
    }

    protected PublicKey getCardPublicKeyFromCert(byte[] fullCertResponse) throws Exception {
        if (fullCertResponse == null || fullCertResponse.length != 135) {
            throw new SecurityException("Card certificate must be exactly 135 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(fullCertResponse);
        buffer.position(4);
        byte[] modBytes = new byte[64];
        byte[] expBytes = new byte[3];
        buffer.get(modBytes);
        buffer.get(expBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(
            new BigInteger(1, modBytes),
            new BigInteger(1, expBytes)
        );
        KeyFactory kf = KeyFactory.getInstance("RSA", "BC");
        return kf.generatePublic(spec);
    }

    public boolean connect() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) {
                System.err.println("[Terminal] No PC/SC smartcard readers found.");
                return false;
            }

            CardTerminal reader = terminals.get(0);
            if (!reader.isCardPresent() && !reader.waitForCardPresent(10000)) {
                System.err.println("[Terminal] No card inserted in reader: " + reader.getName());
                return false;
            }
            this.card = reader.connect("*");
            this.channel = this.card.getBasicChannel();

            CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID);
            ResponseAPDU res = channel.transmit(select);
            if (res.getSW() != 0x9000) {
                System.err.println("[Terminal] Applet SELECT failed with SW="
                        + String.format("%04X", res.getSW())
                        + " for AID " + bytesToHex(APPLET_AID)
                        + ". Check build.xml, BaseTerminal and GP output.");
            }
            return res.getSW() == 0x9000;
        } catch (CardException e) {
            System.err.println("[Terminal] Card connection failed: " + e.getMessage());
            return false;
        }
    }

    public void disconnect(boolean reset) {
        try {
            if (card != null) card.disconnect(reset);
        } catch (CardException e) {
            e.printStackTrace();
        }
    }
    
    protected ResponseAPDU send(CommandAPDU cmd) throws CardException {
        return channel.transmit(cmd);
    }

    protected static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static byte[] configuredAppletAid() {
        // The override lets standalone terminals address an older installed applet
        // without changing the canonical AID used by new CAP builds.
        String value = System.getProperty("card.applet.aid");
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv("CARD_APPLET_AID");
        }
        if (value == null || value.trim().isEmpty()) {
            value = "A0000001020301";
        }
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
}
