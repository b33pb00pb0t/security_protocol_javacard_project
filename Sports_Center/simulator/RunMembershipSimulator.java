import com.licel.jcardsim.base.Simulator;
import com.sports.recreation.MembershipApplet;

import javacard.framework.AID;
import java.util.Arrays;

public final class RunMembershipSimulator {
    private static final String APPLET_AID = "A0000001020301";

    public static void main(String[] args) {
        // Create the Java Card simulator used to run the applet locally.
        Simulator simulator = new Simulator();
        // Convert the applet AID from its hex string form into the binary AID object.
        AID appletAID = createAid(APPLET_AID);
        // Install the applet and select it so subsequent APDU commands reach it.
        simulator.installApplet(appletAID, MembershipApplet.class);
        simulator.selectApplet(appletAID);

        // Build an explicit SELECT APDU for validation in the simulator.
        byte[] aidBytes = hexToBytes(APPLET_AID);
        byte[] selectApdu = new byte[5 + aidBytes.length];
        selectApdu[0] = (byte) 0x00;
        selectApdu[1] = (byte) 0xA4;
        selectApdu[2] = (byte) 0x04;
        selectApdu[3] = (byte) 0x00;
        selectApdu[4] = (byte) aidBytes.length;
        System.arraycopy(aidBytes, 0, selectApdu, 5, aidBytes.length);

        // Send SELECT and then the INITIALIZE APDU.
        byte[] selectResponse = simulator.transmitCommand(selectApdu);
        byte[] initApdu = new byte[5 + 132];
        initApdu[0] = (byte) 0xB0;
        initApdu[1] = (byte) 0x10;
        initApdu[2] = 0x00;
        initApdu[3] = 0x00;
        initApdu[4] = (byte) 0x84;

        // Fill the 132-byte payload with a deterministic test pattern.
        // The applet treats this as 64 bytes of modulus + 64 bytes of exponent + 4 bytes of card ID.
        for (int i = 0; i < 132; i++) {
            initApdu[5 + i] = (byte) (i & 0xFF);
        }

        byte[] initResponse = simulator.transmitCommand(initApdu);

        // Send GET_CARD_ID command to retrieve the card's ID.
        byte[] getCardIdApdu = new byte[]{(byte) 0xB0, (byte) 0x60, 0x00, 0x00};
        byte[] getCardIdResponse = simulator.transmitCommand(getCardIdApdu);

        // Separate the returned data bytes from the trailing two-byte status word.
        int selectSw = ((selectResponse[selectResponse.length - 2] & 0xFF) << 8)
            | (selectResponse[selectResponse.length - 1] & 0xFF);
        int initSw = ((initResponse[initResponse.length - 2] & 0xFF) << 8)
            | (initResponse[initResponse.length - 1] & 0xFF);
        int getCardIdSw = ((getCardIdResponse[getCardIdResponse.length - 2] & 0xFF) << 8)
            | (getCardIdResponse[getCardIdResponse.length - 1] & 0xFF);

        // Print the decoded response so the simulator output can be verified easily.
        System.out.println("SELECT_SW=" + String.format("%04X", selectSw));
        System.out.println("INIT_SW=" + String.format("%04X", initSw));
        System.out.println("INIT_DATA=" + toHex(Arrays.copyOf(initResponse, initResponse.length - 2)));
        System.out.println("GET_CARD_ID_SW=" + String.format("%04X", getCardIdSw));
        System.out.println("GET_CARD_ID_DATA=" + toHex(Arrays.copyOf(getCardIdResponse, getCardIdResponse.length - 2)));
    }

    private static String toHex(byte[] bytes) {
        // Convert a byte array to uppercase hexadecimal text.
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private static AID createAid(String hex) {
        // Build the AID instance from the hexadecimal identifier string.
        byte[] bytes = hexToBytes(hex);
        return new AID(bytes, (short) 0, (byte) bytes.length);
    }

    private static byte[] hexToBytes(String hex) {
        // Parse the string two characters at a time into raw byte values.
        int length = hex.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
