import com.licel.jcardsim.base.Simulator;
import applet.MembershipApplet;
import javacard.framework.AID;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Scanner;

/**
 * Harness for simulating the Sports Center Membership protocol.
 * This class coordinates the interaction between the Applet logic
 * and the simulated Master/Administrator terminals.
 */
public final class RunMembershipSimulator {
    
    // AID defined in your BaseTerminal and Applet sources
    private static final byte[] AID_BYTES = {
        (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01, 
        (byte) 0x02, (byte) 0x03, (byte) 0x01 
    };

    private static final byte CLA_PROPRIETARY = (byte) 0xB0; 

    public static void main(String[] args) throws Exception {
        // 1. Initialize Simulator and Applet
        Simulator simulator = new Simulator();
        // Install the applet and capture the boolean result
        AID appletAID = new AID(AID_BYTES, (short) 0, (byte) AID_BYTES.length);

        Object result = simulator.installApplet(appletAID, MembershipApplet.class);

        // 3. Print the operation success in English
        if (result != null) {
            System.out.println("Applet Installation: SUCCESS (SW: 9000)");
        } else {
            System.out.println("Applet Installation: FAILED");
        }
        
        System.out.println("--- JCARDSIM SIMULATOR STARTED ---");
        boolean selected = simulator.selectApplet(appletAID);
        System.out.println("Applet Selection: " + (selected ? "OK (9000)" : "FAILED"));

        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.println("\n--- SELECT TERMINAL TO SIMULATE ---");
            System.out.println("1. [MASTER] Initialize Keys and Certificate");
            System.out.println("2. [ADMIN]  Activate Card");
            System.out.println("3. [ADMIN]  Block Card");
            System.out.println("4. Exit");
            System.out.print("Choice > ");
            
            if (!scanner.hasNextInt()) break;
            int choice = scanner.nextInt();
            if (choice == 4) break;

            switch (choice) {
                case 1:
                    runMasterPhase(simulator);
                    break;
                case 2:
                    runAdminActivate(simulator);
                    break;
                case 3:
                    runAdminBlock(simulator);
                    break;
                default:
                    System.out.println("Invalid choice.");
            }
        }
        System.out.println("Closing simulator...");
        scanner.close();
    }

    private static void runMasterPhase(Simulator sim) {      
        try {
            System.out.println("\n[MT] Starting Provisioning (Generating NEW keys)...");

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(512); // standard 512-bit for your applet

            // Generating pairs
            KeyPair masterKeyPair = keyGen.generateKeyPair();
            KeyPair cardKeyPair = keyGen.generateKeyPair();
            
            // Extracting components
            RSAPrivateKey cardPriv = (RSAPrivateKey) cardKeyPair.getPrivate();
            RSAPublicKey cardPub = (RSAPublicKey) cardKeyPair.getPublic();
            RSAPublicKey masterPk = (RSAPublicKey) masterKeyPair.getPublic();

            // --- COMMAND SENDING ---

            byte[] initPayload = new byte[128];
            System.arraycopy(toFixedByteArray(cardPriv.getModulus(), 64), 0, initPayload, 0, 64);
            System.arraycopy(toFixedByteArray(cardPriv.getPrivateExponent(), 64), 0, initPayload, 64, 64);
            sendCommand(sim, (byte)0x10, initPayload, "Private Key Initialization");

            byte[] masterPayload = new byte[67];
            System.arraycopy(toFixedByteArray(masterPk.getModulus(), 64), 0, masterPayload, 0, 64);
            System.arraycopy(toFixedByteArray(masterPk.getPublicExponent(), 3), 0, masterPayload, 64, 3);
            sendCommand(sim, (byte)0x12, masterPayload, "Master Public Key Loading");

            byte[] certData = new byte[135];
            byte[] cardId = {0x00, 0x00, 0x00, 0x01};
            System.arraycopy(cardId, 0, certData, 0, 4);
            System.arraycopy(toFixedByteArray(cardPub.getModulus(), 64), 0, certData, 4, 64);
            System.arraycopy(toFixedByteArray(cardPub.getPublicExponent(), 3), 0, certData, 68, 3);
            sendCommand(sim, (byte)0x11, certData, "Certificate Loading");

            // --- DEBUG PRINT ---
            System.out.println("\n--- GENERATED CARD KEYS ---");
            System.out.println("Modulus: " + toHexString(cardPub.getModulus()));
            System.out.println("Public Exponent:  " + cardPub.getPublicExponent());
            System.out.println("Private Exponent: " + toHexString(cardPriv.getPrivateExponent()));

        } catch (Exception e) {
            System.err.println("Error during Master Phase: " + e.getMessage());
        }
    }

    // --- ADMIN TERMINAL LOGIC (Adapted from Source 2) ---
    private static void runAdminActivate(Simulator sim) {
        System.out.println("\n[AT] Activating Card...");
        // Payload: Member ID (4 bytes) + activation date (4 bytes) + expiry date (4 bytes)
        byte[] payload = {
            0x00, 0x00, 0x04, (byte)0xD2, // ID 1234
            0x20, 0x26, 0x06, 0x03,       // Activation date 2026-06-03
            0x20, 0x26, 0x12, 0x31        // Expiry date 2026-12-31
        };
        sendCommand(sim, (byte)0x13, payload, "Card Activation");
    }

    private static void runAdminBlock(Simulator sim) {
        System.out.println("\n[AT] Blocking Card...");
        sendCommand(sim, (byte)0x14, null, "Card Blocking"); //[cite: 1, 2]
    }

    // --- HELPER METHODS ---
    private static void sendCommand(Simulator sim, byte ins, byte[] data, String label) {
        int lc = (data != null) ? data.length : 0;
        byte[] apdu = new byte[5 + lc];
        apdu[0] = CLA_PROPRIETARY;
        apdu[1] = ins;
        apdu[2] = 0x00; // P1
        apdu[3] = 0x00; // P2
        apdu[4] = (byte) lc;
        if (data != null) System.arraycopy(data, 0, apdu, 5, lc);

        byte[] response = sim.transmitCommand(apdu);
        int sw = ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
        
        System.out.println(" >> " + label + " Response SW: " + String.format("%04X", sw));
        if (sw == 0x9000) {
            System.out.println("    Status: SUCCESS");
        } else {
            System.out.println("    Status: FAILED");
        }
    }

    private static byte[] toFixedByteArray(BigInteger val, int len) {
        byte[] src = val.toByteArray();
        byte[] dest = new byte[len];
        int startSrc = (src.length > len) ? src.length - len : 0;
        int lenToCopy = Math.min(src.length, len);
        System.arraycopy(src, startSrc, dest, len - lenToCopy, lenToCopy);
        return dest;
    }

    private static String toHexString(BigInteger val) {
        return String.format("%0128x", val); // Forza 128 caratteri hex per moduli a 512 bit
    }
}
