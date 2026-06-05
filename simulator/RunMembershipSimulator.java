import com.licel.jcardsim.base.Simulator;
import applet.MembershipApplet;
import applet.ProtocolConstants;
import backend.ApduDateCodec;
import backend.CardId;
import javacard.framework.AID;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDate;
import java.util.Arrays;
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
    private static KeyPair masterKeyPair;
    private static KeyPair adminKeyPair;

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

            masterKeyPair = keyGen.generateKeyPair();
            adminKeyPair = keyGen.generateKeyPair();
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

            byte[] certData = new byte[ProtocolConstants.CARD_CERTIFICATE_LENGTH];
            byte[] cardId = {0x01, 0x02, 0x03, 0x04};
            certData[ProtocolConstants.CERT_ROLE_OFFSET] = ProtocolConstants.ROLE_CARD;
            System.arraycopy(cardId, 0, certData, ProtocolConstants.CERT_ID_OFFSET, 4);
            System.arraycopy(toFixedByteArray(cardPub.getModulus(), 64), 0, certData,
                    ProtocolConstants.CERT_MODULUS_OFFSET, 64);
            System.arraycopy(toFixedByteArray(cardPub.getPublicExponent(), 3), 0, certData,
                    ProtocolConstants.CERT_EXPONENT_OFFSET, 3);
            Signature certificateSigner = Signature.getInstance("SHA1withRSA");
            certificateSigner.initSign(masterKeyPair.getPrivate());
            certificateSigner.update(certData, 0, ProtocolConstants.CERTIFICATE_BODY_LENGTH);
            System.arraycopy(certificateSigner.sign(), 0, certData,
                    ProtocolConstants.CERT_SIGNATURE_OFFSET, ProtocolConstants.SIGNATURE_LENGTH);
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
        try {
            LocalDate currentDate = LocalDate.now();
            byte[] operationData = new byte[ProtocolConstants.ADMIN_ACTIVATE_DATA_LENGTH];
            operationData[0] = ProtocolConstants.OP_ACTIVATE;
            System.arraycopy(CardId.toBytes("1234"), 0, operationData, 1, 4);
            System.arraycopy(ApduDateCodec.encode(currentDate), 0, operationData, 5, 4);
            System.arraycopy(ApduDateCodec.encode(currentDate.plusYears(1)), 0, operationData, 9, 4);
            sendCommand(sim, (byte)0x13,
                    buildAuthenticatedAdminPayload(sim, ProtocolConstants.OP_ACTIVATE, operationData),
                    "Authenticated Card Activation");
        } catch (Exception e) {
            System.err.println("Error during Admin Activation: " + e.getMessage());
        }
    }

    private static void runAdminBlock(Simulator sim) {
        System.out.println("\n[AT] Blocking Card...");
        try {
            byte[] operationData = new byte[ProtocolConstants.ADMIN_BLOCK_DATA_LENGTH];
            operationData[0] = ProtocolConstants.OP_BLOCK;
            System.arraycopy(CardId.toBytes("1234"), 0, operationData, 1, 4);
            sendCommand(sim, (byte)0x14,
                    buildAuthenticatedAdminPayload(sim, ProtocolConstants.OP_BLOCK, operationData),
                    "Authenticated Card Blocking");
        } catch (Exception e) {
            System.err.println("Error during Admin Block: " + e.getMessage());
        }
    }

    private static byte[] buildAuthenticatedAdminPayload(Simulator sim, byte operation,
                                                         byte[] operationData) throws Exception {
        if (masterKeyPair == null || adminKeyPair == null) {
            throw new IllegalStateException("Run the Master provisioning phase first.");
        }
        byte[] nonce = sendCommandWithResponse(sim, (byte) 0x30, null, "Admin Challenge");
        if (nonce.length != ProtocolConstants.NONCE_LENGTH) {
            throw new IllegalStateException("Admin challenge returned " + nonce.length + " bytes");
        }

        byte[] adminCertificate = signedCertificate(ProtocolConstants.ROLE_ADMIN_TERMINAL,
                new byte[] {0x0A, 0x0B, 0x0C, 0x0E}, (RSAPublicKey) adminKeyPair.getPublic());
        byte[] signatureInput = new byte[operationData.length + nonce.length];
        System.arraycopy(operationData, 0, signatureInput, 0, operationData.length);
        System.arraycopy(nonce, 0, signatureInput, operationData.length, nonce.length);
        byte[] adminSignature = sign(adminKeyPair, signatureInput);

        int expectedLength = operation == ProtocolConstants.OP_ACTIVATE
                ? ProtocolConstants.ADMIN_ACTIVATE_PAYLOAD_LENGTH
                : ProtocolConstants.ADMIN_BLOCK_PAYLOAD_LENGTH;
        byte[] payload = new byte[expectedLength];
        System.arraycopy(operationData, 0, payload, 0, operationData.length);
        System.arraycopy(adminCertificate, 0, payload, operationData.length, adminCertificate.length);
        System.arraycopy(adminSignature, 0, payload, operationData.length + adminCertificate.length,
                adminSignature.length);
        return payload;
    }

    private static byte[] signedCertificate(byte role, byte[] id, RSAPublicKey publicKey) throws Exception {
        byte[] certificate = new byte[ProtocolConstants.CARD_CERTIFICATE_LENGTH];
        certificate[ProtocolConstants.CERT_ROLE_OFFSET] = role;
        System.arraycopy(id, 0, certificate, ProtocolConstants.CERT_ID_OFFSET, 4);
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificate,
                ProtocolConstants.CERT_MODULUS_OFFSET, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificate,
                ProtocolConstants.CERT_EXPONENT_OFFSET, 3);
        System.arraycopy(sign(masterKeyPair, Arrays.copyOf(certificate,
                ProtocolConstants.CERTIFICATE_BODY_LENGTH)), 0, certificate,
                ProtocolConstants.CERT_SIGNATURE_OFFSET, ProtocolConstants.SIGNATURE_LENGTH);
        return certificate;
    }

    private static byte[] sign(KeyPair keyPair, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        byte[] signed = signature.sign();
        if (signed.length != ProtocolConstants.SIGNATURE_LENGTH) {
            throw new IllegalStateException("Expected 64-byte RSA signature, got " + signed.length);
        }
        return signed;
    }

    private static void sendCommand(Simulator sim, byte ins, byte[] data, String label) {
        sendCommandWithResponse(sim, ins, data, label);
    }

    private static byte[] sendCommandWithResponse(Simulator sim, byte ins, byte[] data, String label) {
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
        return Arrays.copyOf(response, response.length - 2);
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
