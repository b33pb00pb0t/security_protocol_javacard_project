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

public abstract class BaseTerminal {
    protected Card card;
    protected CardChannel channel;
    protected PublicKey masterPublicKey;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    protected static final byte[] APPLET_AID = {
    (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, 
    (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x02 
    };

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
        if (masterPublicKey == null) {
            throw new SecurityException("Master Public Key not loaded. Cannot verify card.");
        }

        // 1. Fetch the certificate from the card
        ResponseAPDU res = send(new CommandAPDU(0xB0, INS_GET_CERT, 0x00, 0x00, 256));
        if (res.getSW() != 0x9000) {
            throw new CardException("Failed to retrieve certificate. SW: " + Integer.toHexString(res.getSW()));
        }

        byte[] fullResponse = res.getData();
        // The certificate format as per MT design:
        // ID_C (4) + Modulus (64) + Exponent (3) + Signature_MT (64) = 135 bytes
        if (fullResponse.length < 135) {
            throw new SecurityException("Invalid certificate length received from card.");
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

        // 2. Prepare the data that was signed (ID_C + Modulus + Exponent)
        byte[] signedData = new byte[4 + 64 + 3];
        System.arraycopy(fullResponse, 0, signedData, 0, signedData.length);

        // 3. Verify the Master's signature on the card's data
        Signature verifier = Signature.getInstance("SHA256withRSA", "BC");
        verifier.initVerify(masterPublicKey);
        verifier.update(signedData);

        if (!verifier.verify(signatureMT)) {
            throw new SecurityException("CRITICAL: Card certificate signature verification failed!");
        }

        System.out.println("[Terminal] Card Certificate verified successfully via PK_M.");
        return idC;
    }

    /**
     * Helper to reconstruct the Card's Public Key from the verified certificate.
     */
    protected PublicKey getCardPublicKeyFromCert(byte[] fullCertResponse) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(fullCertResponse);
        buffer.position(4); // Skip ID
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

    // --- Standard Connectivity Methods ---

    public boolean connect() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) return false;

            CardTerminal reader = terminals.get(0);
            this.card = reader.connect("*");
            this.channel = this.card.getBasicChannel();

            CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID);
            ResponseAPDU res = channel.transmit(select);
            return res.getSW() == 0x9000;
        } catch (CardException e) {
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

    protected String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
