import com.licel.jcardsim.base.Simulator;
import applet.MembershipApplet;
import javacard.framework.AID;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;

public final class RunMembershipSimulator {
    private static final String APPLET_AID = "A0000001020301"; 

    public static void main(String[] args) {
        Simulator simulator = new Simulator();
        AID appletAID = createAid(APPLET_AID);
        
        // Install and automatically select the applet
        simulator.installApplet(appletAID, MembershipApplet.class);
        simulator.selectApplet(appletAID);

        // --- PHASE 1: MASTER TERMINAL (MT) - Provisioning ---
        System.out.println("--- MASTER TERMINAL OPERATIONS ---");

        // Generate a real RSA key pair to avoid 6F00 errors
        KeyPair cardKeyPair = generateRsaKeyPair();
        RSAPrivateKey cardPriv = (RSAPrivateKey) cardKeyPair.getPrivate();

        // 1. INS_INITIALIZE_KEY (0x10): Load the card private key
        byte[] initKeyApdu = new byte[5 + 128];
        initKeyApdu[0] = (byte) 0xB0; // CLA
        initKeyApdu[1] = (byte) 0x10; // INS
        initKeyApdu[4] = (byte) 0x80; // Lc (128 bytes)

        // Copy modulus and private exponent into APDU payload
        copyBigInteger(cardPriv.getModulus(), initKeyApdu, 5, 64);
        copyBigInteger(cardPriv.getPrivateExponent(), initKeyApdu, 5 + 64, 64);
        
        byte[] initKeyRes = simulator.transmitCommand(initKeyApdu);
        printStatus("INIT_KEY", initKeyRes);

        // 2. INS_LOAD_CERT (0x11): Load master certificate (dummy data)
        byte[] loadCertApdu = new byte[5 + 135];
        loadCertApdu[0] = (byte) 0xB0;
        loadCertApdu[1] = (byte) 0x11;
        loadCertApdu[4] = (byte) 0x87; // Lc (135 bytes)

        // Fill with dummy certificate data
        for (int i = 0; i < 135; i++) {
            loadCertApdu[5 + i] = (byte) 0xCC;
        }

        byte[] loadCertRes = simulator.transmitCommand(loadCertApdu);
        printStatus("LOAD_CERT", loadCertRes);

        // 3. INS_LOAD_MASTER_KEY (0x12): Load master public key
        KeyPair masterKeyPair = generateRsaKeyPair();
        byte[] masterPKPayload = encodeRsaPublicKey((RSAPublicKey) masterKeyPair.getPublic());
        
        byte[] loadMasterKeyApdu = new byte[5 + 67];
        loadMasterKeyApdu[0] = (byte) 0xB0;
        loadMasterKeyApdu[1] = (byte) 0x12;
        loadMasterKeyApdu[4] = (byte) 0x43; // Lc (67 bytes)

        System.arraycopy(masterPKPayload, 0, loadMasterKeyApdu, 5, 67);

        byte[] loadMasterKeyRes = simulator.transmitCommand(loadMasterKeyApdu);
        printStatus("LOAD_MASTER_KEY", loadMasterKeyRes);


        // --- PHASE 2: ADMINISTRATOR TERMINAL (AT) - Lifecycle Management ---
        System.out.println("\n--- ADMINISTRATOR TERMINAL OPERATIONS ---");

        // 4. INS_ACTIVATE (0x13): Activate member card
        byte[] activateApdu = new byte[5 + 8];
        activateApdu[0] = (byte) 0xB0;
        activateApdu[1] = (byte) 0x13;
        activateApdu[4] = (byte) 0x08; // Lc (8 bytes)

        // Member ID (4 bytes) + Date (4 bytes)
        byte[] activationData = {
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, // Member ID
            (byte)0x20, (byte)0x26, (byte)0x05, (byte)0x03  // Date (YYMMDD format or similar)
        };
        System.arraycopy(activationData, 0, activateApdu, 5, 8);

        byte[] activateRes = simulator.transmitCommand(activateApdu);
        printStatus("ACTIVATE_CARD", activateRes);

        // 5. INS_BLOCK (0x14): Block the card (irreversibility test)
        byte[] blockApdu = new byte[]{(byte) 0xB0, (byte) 0x14, 0x00, 0x00};
        byte[] blockRes = simulator.transmitCommand(blockApdu);
        printStatus("BLOCK_CARD", blockRes);
    }

    // --- HELPER METHODS ---

    // Print APDU status word (SW)
    private static void printStatus(String op, byte[] response) {
        int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
        System.out.println(op + " SW: " + String.format("%04X", sw));
    }

    // Generate a 512-bit RSA key pair
    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(512);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Encode RSA public key into fixed-length byte array
    private static byte[] encodeRsaPublicKey(RSAPublicKey pk) {
        byte[] out = new byte[67];
        copyBigInteger(pk.getModulus(), out, 0, 64);
        copyBigInteger(pk.getPublicExponent(), out, 64, 3);
        return out;
    }

    // Copy BigInteger into fixed-length byte array with padding/truncation
    private static void copyBigInteger(BigInteger val, byte[] dest, int off, int len) {
        byte[] src = val.toByteArray();
        int srcOff = src.length > len ? src.length - len : 0;
        int copyLen = Math.min(src.length, len);
        System.arraycopy(src, srcOff, dest, off + (len - copyLen), copyLen);
    }

    // Convert hex string to AID object
    private static AID createAid(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new AID(b, (short) 0, (byte) b.length);
    }
}