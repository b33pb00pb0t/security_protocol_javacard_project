import com.licel.jcardsim.base.Simulator;
import applet.MembershipApplet;
import backend.ApduDateCodec;
import backend.CardId;
import javacard.framework.AID;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDate;
import java.util.Scanner;

/**
 * Interactive raw-APDU lifecycle demo. The GUI and SimulatorRegressionTest
 * use JCardSimGateway for the complete simulator workflow.
 */
public final class RunMembershipSimulator {
    private static final byte[] AID_BYTES = {
        (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x01, 
        (byte) 0x02, (byte) 0x03, (byte) 0x01 
    };

    private static final byte CLA_PROPRIETARY = (byte) 0xB0; 

    public static void main(String[] args) throws Exception {
        Simulator simulator = new Simulator();
        AID appletAID = new AID(AID_BYTES, (short) 0, (byte) AID_BYTES.length);

        Object result = simulator.installApplet(appletAID, MembershipApplet.class);

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
            keyGen.initialize(512);

            KeyPair masterKeyPair = keyGen.generateKeyPair();
            KeyPair cardKeyPair = keyGen.generateKeyPair();
            
            RSAPrivateKey cardPriv = (RSAPrivateKey) cardKeyPair.getPrivate();
            RSAPublicKey cardPub = (RSAPublicKey) cardKeyPair.getPublic();
            RSAPublicKey masterPk = (RSAPublicKey) masterKeyPair.getPublic();

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

            System.out.println("\n--- GENERATED CARD KEYS ---");
            System.out.println("Modulus: " + toHexString(cardPub.getModulus()));
            System.out.println("Public Exponent:  " + cardPub.getPublicExponent());
            System.out.println("Private Exponent: " + toHexString(cardPriv.getPrivateExponent()));

        } catch (Exception e) {
            System.err.println("Error during Master Phase: " + e.getMessage());
        }
    }

    private static void runAdminActivate(Simulator sim) {
        System.out.println("\n[AT] Activating Card...");
        // Activation: memberId(4) || currentDate(4) || expiryDate(4).
        LocalDate currentDate = LocalDate.now();
        byte[] payload = new byte[12];
        System.arraycopy(CardId.toBytes("1234"), 0, payload, 0, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate), 0, payload, 4, 4);
        System.arraycopy(ApduDateCodec.encode(currentDate.plusYears(1)), 0, payload, 8, 4);
        sendCommand(sim, (byte)0x13, payload, "Card Activation");
    }

    private static void runAdminBlock(Simulator sim) {
        System.out.println("\n[AT] Blocking Card...");
        sendCommand(sim, (byte)0x14, null, "Card Blocking");
    }

    private static void sendCommand(Simulator sim, byte ins, byte[] data, String label) {
        int lc = (data != null) ? data.length : 0;
        byte[] apdu = new byte[5 + lc];
        apdu[0] = CLA_PROPRIETARY;
        apdu[1] = ins;
        apdu[2] = 0x00;
        apdu[3] = 0x00;
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
        return String.format("%0128x", val);
    }
}
