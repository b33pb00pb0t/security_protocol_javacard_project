import com.licel.jcardsim.base.Simulator;
import com.sports.recreation.MembershipApplet;

import javacard.framework.AID;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
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

        // Send SELECT and then the two-phase initialization.
        byte[] selectResponse = simulator.transmitCommand(selectApdu);

        // Phase 1: Send 128-byte key provisioning (modulus + exponent).
        byte[] initKeyApdu = new byte[5 + 128];
        initKeyApdu[0] = (byte) 0xB0;
        initKeyApdu[1] = (byte) 0x10;  // INS_INITIALIZE_KEY
        initKeyApdu[2] = 0x00;
        initKeyApdu[3] = 0x00;
        initKeyApdu[4] = (byte) 0x80;

        // Fill the 128-byte payload with a deterministic test pattern.
        // The applet treats this as 64 bytes of modulus followed by 64 bytes of exponent.
        for (int i = 0; i < 128; i++) {
            initKeyApdu[5 + i] = (byte) (i & 0xFF);
        }

        byte[] initKeyResponse = simulator.transmitCommand(initKeyApdu);

        // Phase 2: Send 135-byte certificate (ID_C + PK_C + MT signature).
        byte[] loadCertApdu = new byte[5 + 135];
        loadCertApdu[0] = (byte) 0xB0;
        loadCertApdu[1] = (byte) 0x11;  // INS_LOAD_CERT
        loadCertApdu[2] = 0x00;
        loadCertApdu[3] = 0x00;
        loadCertApdu[4] = (byte) 0x87;

        // Fill the 135-byte payload with a deterministic test pattern.
        // Structure: 4 bytes ID_C + 64 bytes modulus + 3 bytes exponent + 64 bytes signature.
        for (int i = 0; i < 135; i++) {
            loadCertApdu[5 + i] = (byte) ((i + 128) & 0xFF);
        }

        byte[] loadCertResponse = simulator.transmitCommand(loadCertApdu);

        // Generate a real RSA key pair for the Master and one for the terminal so
        // the Tier 2 mutual-authentication verification step can be exercised end-to-end.
        KeyPair masterKeyPair = generateRsaKeyPair();
        KeyPair terminalKeyPair = generateRsaKeyPair();

        // Phase 3: Load the Master public key used to verify offline certificate signatures.
        byte[] loadMasterKeyApdu = new byte[5 + 67];
        loadMasterKeyApdu[0] = (byte) 0xB0;
        loadMasterKeyApdu[1] = (byte) 0x12;  // INS_LOAD_MASTER_KEY
        loadMasterKeyApdu[2] = 0x00;
        loadMasterKeyApdu[3] = 0x00;
        loadMasterKeyApdu[4] = (byte) 0x43;

        byte[] masterPublicKeyPayload = encodeRsaPublicKey((RSAPublicKey) masterKeyPair.getPublic());
        System.arraycopy(masterPublicKeyPayload, 0, loadMasterKeyApdu, 5, masterPublicKeyPayload.length);

        byte[] loadMasterKeyResponse = simulator.transmitCommand(loadMasterKeyApdu);

        // Phase 4: Query the stored certificate.
        byte[] getCertApdu = new byte[]{(byte) 0xB0, (byte) 0x60, 0x00, 0x00};
        byte[] getCertResponse = simulator.transmitCommand(getCertApdu);

        // Phase 5: Activate the card (transition to STATE_ACTIVE).
        byte[] activateApdu = new byte[5 + 8];
        activateApdu[0] = (byte) 0xB0;
        activateApdu[1] = (byte) 0x13;  // INS_ACTIVATE
        activateApdu[2] = 0x00;
        activateApdu[3] = 0x00;
        activateApdu[4] = (byte) 0x08;

        // Fill with test member ID (4 bytes) and current date (4 bytes).
        for (int i = 0; i < 8; i++) {
            activateApdu[5 + i] = (byte) (i & 0xFF);
        }

        byte[] activateResponse = simulator.transmitCommand(activateApdu);

        // Phase 6: Test Tier 2 step 1 with a 16-byte terminal nonce.
        byte[] tier2Step1Apdu = new byte[5 + 16];
        tier2Step1Apdu[0] = (byte) 0xB0;
        tier2Step1Apdu[1] = (byte) 0x21;  // INS_T2_STEP1
        tier2Step1Apdu[2] = 0x00;
        tier2Step1Apdu[3] = 0x00;
        tier2Step1Apdu[4] = (byte) 0x10;

        for (int i = 0; i < 16; i++) {
            tier2Step1Apdu[5 + i] = (byte) ((i + 16) & 0xFF);
        }

        byte[] tier2Step1Response = simulator.transmitCommand(tier2Step1Apdu);

        byte[] tier2Step1Data = Arrays.copyOf(tier2Step1Response, tier2Step1Response.length - 2);
        byte[] nC = Arrays.copyOfRange(tier2Step1Data, 0, 16);

        byte[] currentDate = new byte[] {(byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07};
        byte[] terminalId = new byte[] {(byte) 0x21, (byte) 0x22, (byte) 0x23, (byte) 0x24};
        byte[] terminalCertData = buildTerminalCertData(terminalId, (RSAPublicKey) terminalKeyPair.getPublic());
        byte[] terminalCertSignature = signSha1WithRsa(terminalCertData, masterKeyPair.getPrivate());

        byte[] sigma2Input = new byte[20];
        System.arraycopy(nC, 0, sigma2Input, 0, 16);
        System.arraycopy(currentDate, 0, sigma2Input, 16, 4);
        byte[] sigma2 = signSha1WithRsa(sigma2Input, terminalKeyPair.getPrivate());

        // Phase 7: Complete Tier 2 mutual authentication.
        byte[] tier2Step2Apdu = new byte[5 + 203];
        tier2Step2Apdu[0] = (byte) 0xB0;
        tier2Step2Apdu[1] = (byte) 0x22;  // INS_T2_STEP2
        tier2Step2Apdu[2] = 0x00;
        tier2Step2Apdu[3] = 0x00;
        tier2Step2Apdu[4] = (byte) 0xCB;

        System.arraycopy(sigma2, 0, tier2Step2Apdu, 5, 64);
        System.arraycopy(terminalCertData, 0, tier2Step2Apdu, 5 + 64, 71);
        System.arraycopy(terminalCertSignature, 0, tier2Step2Apdu, 5 + 135, 64);
        System.arraycopy(currentDate, 0, tier2Step2Apdu, 5 + 199, 4);

        byte[] tier2Step2Response = simulator.transmitCommand(tier2Step2Apdu);

        // Phase 8: Test Tier 1 check-in with a 16-byte nonce.
        byte[] checkInT1Apdu = new byte[5 + 16];
        checkInT1Apdu[0] = (byte) 0xB0;
        checkInT1Apdu[1] = (byte) 0x20;  // INS_CHECKIN_T1
        checkInT1Apdu[2] = 0x00;
        checkInT1Apdu[3] = 0x00;
        checkInT1Apdu[4] = (byte) 0x10;

        // Fill with a deterministic nonce pattern.
        for (int i = 0; i < 16; i++) {
            checkInT1Apdu[5 + i] = (byte) (i & 0xFF);
        }

        byte[] checkInT1Response = simulator.transmitCommand(checkInT1Apdu);

        // Separate the returned data bytes from the trailing two-byte status word.
        int selectSw = ((selectResponse[selectResponse.length - 2] & 0xFF) << 8)
            | (selectResponse[selectResponse.length - 1] & 0xFF);
        int initKeySw = ((initKeyResponse[initKeyResponse.length - 2] & 0xFF) << 8)
            | (initKeyResponse[initKeyResponse.length - 1] & 0xFF);
        int loadCertSw = ((loadCertResponse[loadCertResponse.length - 2] & 0xFF) << 8)
            | (loadCertResponse[loadCertResponse.length - 1] & 0xFF);
        int loadMasterKeySw = ((loadMasterKeyResponse[loadMasterKeyResponse.length - 2] & 0xFF) << 8)
            | (loadMasterKeyResponse[loadMasterKeyResponse.length - 1] & 0xFF);
        int getCertSw = ((getCertResponse[getCertResponse.length - 2] & 0xFF) << 8)
            | (getCertResponse[getCertResponse.length - 1] & 0xFF);
        int activateSw = ((activateResponse[activateResponse.length - 2] & 0xFF) << 8)
            | (activateResponse[activateResponse.length - 1] & 0xFF);
        int tier2Step1Sw = ((tier2Step1Response[tier2Step1Response.length - 2] & 0xFF) << 8)
            | (tier2Step1Response[tier2Step1Response.length - 1] & 0xFF);
        int tier2Step2Sw = ((tier2Step2Response[tier2Step2Response.length - 2] & 0xFF) << 8)
            | (tier2Step2Response[tier2Step2Response.length - 1] & 0xFF);
        int checkInT1Sw = ((checkInT1Response[checkInT1Response.length - 2] & 0xFF) << 8)
            | (checkInT1Response[checkInT1Response.length - 1] & 0xFF);

        // Print the decoded response so the simulator output can be verified easily.
        System.out.println("SELECT_SW=" + String.format("%04X", selectSw));
        System.out.println("INIT_KEY_SW=" + String.format("%04X", initKeySw));
        System.out.println("LOAD_CERT_SW=" + String.format("%04X", loadCertSw));
        System.out.println("LOAD_MASTER_KEY_SW=" + String.format("%04X", loadMasterKeySw));
        System.out.println("GET_CERT_SW=" + String.format("%04X", getCertSw));
        System.out.println("GET_CERT_DATA=" + toHex(Arrays.copyOf(getCertResponse, getCertResponse.length - 2)));
        System.out.println("ACTIVATE_SW=" + String.format("%04X", activateSw));
        System.out.println("T2_STEP1_SW=" + String.format("%04X", tier2Step1Sw));
        System.out.println("T2_STEP1_DATA_LEN=" + (tier2Step1Response.length - 2));
        System.out.println("T2_STEP1_DATA=" + toHex(Arrays.copyOf(tier2Step1Response, tier2Step1Response.length - 2)));
        System.out.println("T2_STEP2_SW=" + String.format("%04X", tier2Step2Sw));
        System.out.println("T2_STEP2_COUNTER=" + (tier2Step2Response[0] & 0xFF));
        System.out.println("CHECKIN_T1_SW=" + String.format("%04X", checkInT1Sw));
        System.out.println("CHECKIN_T1_SIG_LEN=" + (checkInT1Response.length - 2));
        System.out.println("CHECKIN_T1_SIG=" + toHex(Arrays.copyOf(checkInT1Response, checkInT1Response.length - 2)));
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(512);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate RSA key pair", exception);
        }
    }

    private static byte[] encodeRsaPublicKey(RSAPublicKey publicKey) {
        byte[] encoded = new byte[67];
        copyBigInteger(publicKey.getModulus(), encoded, 0, 64);
        copyBigInteger(publicKey.getPublicExponent(), encoded, 64, 3);
        return encoded;
    }

    private static byte[] buildTerminalCertData(byte[] terminalId, RSAPublicKey publicKey) {
        byte[] certData = new byte[71];
        System.arraycopy(terminalId, 0, certData, 0, 4);
        copyBigInteger(publicKey.getModulus(), certData, 4, 64);
        copyBigInteger(publicKey.getPublicExponent(), certData, 68, 3);
        return certData;
    }

    private static byte[] signSha1WithRsa(byte[] data, PrivateKey privateKey) {
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA1withRSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign data", exception);
        }
    }

    private static void copyBigInteger(BigInteger value, byte[] destination, int offset, int length) {
        byte[] source = value.toByteArray();
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = source.length - sourceOffset;
        int destinationOffset = offset + (length - copyLength);
        System.arraycopy(source, sourceOffset, destination, destinationOffset, copyLength);
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
