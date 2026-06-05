package terminals;

import applet.ProtocolConstants;
import backend.ApduDateCodec;
import backend.CardId;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

public class AdminTerminal extends BaseTerminal {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_ADMIN_CHALLENGE = (byte) 0x30;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Path HARDWARE_KEY_DIRECTORY = Paths.get("hardware_keys");
    private static final byte[] ADMIN_TERMINAL_ID = new byte[] {0x0A, 0x0B, 0x0C, 0x0E};

    private KeyPair adminKeyPair;
    private byte[] adminCertificateBody;
    private byte[] adminMasterSignature;

    private void syncWithDatabase(String memberId, String phone, String action) {
        System.out.println("[AT][DB-LOG] Action: " + action);
        System.out.println("[AT][DB-LOG] Member ID (Verified): " + memberId);
        System.out.println("[AT][DB-LOG] Linked Phone: " + (phone == null || phone.isEmpty() ? "N/A" : phone));
    }

    private void processActivate(Scanner scanner) throws Exception {
        byte[] cardIdentity = verifyAndGetIdFromCert();
        System.out.println("[AT] Authentic blank card found. Card certificate ID: " + bytesToHex(cardIdentity));

        System.out.print("Enter Member ID to assign: ");
        byte[] memberId = CardId.toBytes(scanner.nextLine().trim());
        String idHex = bytesToHex(memberId);

        System.out.print("Enter Expiry Date (YYYYMMDD): ");
        LocalDate expiryDate = parseDate(scanner.nextLine());
        byte[] operationData = new byte[ProtocolConstants.ADMIN_ACTIVATE_DATA_LENGTH];
        operationData[0] = ProtocolConstants.OP_ACTIVATE;
        System.arraycopy(memberId, 0, operationData, 1, 4);
        System.arraycopy(ApduDateCodec.encode(LocalDate.now()), 0, operationData, 5, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, operationData, 9, 4);

        ResponseAPDU response = send(new CommandAPDU(CLA_PROPRIETARY, INS_ACTIVATE, 0x00, 0x00,
                buildAuthenticatedAdminPayload(ProtocolConstants.OP_ACTIVATE, operationData)));
        if (response.getSW() != 0x9000) {
            System.err.println("[AT] Activation failed. SW: " + String.format("%04X", response.getSW()));
            return;
        }

        // Phone remains backend-only and is never included in the card APDU.
        System.out.print("Enter Customer Phone Number for ID " + idHex + ": ");
        syncWithDatabase(idHex, scanner.nextLine().trim(), "ACTIVATION");
        System.out.println("[AT] Card activated successfully.");
    }

    private void processBlock(Scanner scanner) throws Exception {
        byte[] cardIdentity = verifyAndGetIdFromCert();
        System.out.println("[AT] Authentic card found. Card certificate ID: " + bytesToHex(cardIdentity));
        System.out.print("Enter assigned Member ID to block: ");
        byte[] memberId = CardId.toBytes(scanner.nextLine().trim());
        String idHex = bytesToHex(memberId);
        byte[] operationData = new byte[ProtocolConstants.ADMIN_BLOCK_DATA_LENGTH];
        operationData[0] = ProtocolConstants.OP_BLOCK;
        System.arraycopy(memberId, 0, operationData, 1, 4);
        ResponseAPDU response = send(new CommandAPDU(CLA_PROPRIETARY, INS_BLOCK, 0x00, 0x00,
                buildAuthenticatedAdminPayload(ProtocolConstants.OP_BLOCK, operationData)));
        if (response.getSW() != 0x9000) {
            System.err.println("[AT] Block failed. SW: " + String.format("%04X", response.getSW()));
            return;
        }
        syncWithDatabase(idHex, null, "BLOCK");
        System.out.println("[AT] Card blocked successfully.");
    }

    private byte[] buildAuthenticatedAdminPayload(byte operation, byte[] operationData) throws Exception {
        ResponseAPDU challenge = send(new CommandAPDU(CLA_PROPRIETARY, INS_ADMIN_CHALLENGE, 0x00, 0x00,
                ProtocolConstants.NONCE_LENGTH));
        if (challenge.getSW() != 0x9000) {
            throw new IllegalStateException("Admin challenge failed. SW: "
                    + String.format("%04X", challenge.getSW()));
        }
        if (challenge.getData().length != ProtocolConstants.NONCE_LENGTH) {
            throw new IllegalStateException("Admin challenge returned " + challenge.getData().length
                    + " bytes; expected " + ProtocolConstants.NONCE_LENGTH);
        }

        int expectedLength = operation == ProtocolConstants.OP_ACTIVATE
                ? ProtocolConstants.ADMIN_ACTIVATE_PAYLOAD_LENGTH
                : ProtocolConstants.ADMIN_BLOCK_PAYLOAD_LENGTH;
        byte[] payload = new byte[expectedLength];
        System.arraycopy(operationData, 0, payload, 0, operationData.length);
        System.arraycopy(adminCertificateBody(), 0, payload, operationData.length,
                ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        System.arraycopy(adminMasterSignature(), 0, payload,
                operationData.length + ProtocolConstants.CERTIFICATE_BODY_LENGTH,
                ProtocolConstants.SIGNATURE_LENGTH);
        System.arraycopy(signAdminOperation(operationData, challenge.getData()), 0, payload,
                operationData.length + ProtocolConstants.CERTIFICATE_BODY_LENGTH
                        + ProtocolConstants.SIGNATURE_LENGTH,
                ProtocolConstants.SIGNATURE_LENGTH);
        return payload;
    }

    private byte[] adminCertificateBody() throws Exception {
        if (adminCertificateBody == null) {
            KeyPair keyPair = adminKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            byte[] certificate = new byte[ProtocolConstants.CERTIFICATE_BODY_LENGTH];
            certificate[ProtocolConstants.CERT_ROLE_OFFSET] = ProtocolConstants.ROLE_ADMIN_TERMINAL;
            System.arraycopy(ADMIN_TERMINAL_ID, 0, certificate, ProtocolConstants.CERT_ID_OFFSET, 4);
            System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificate,
                    ProtocolConstants.CERT_MODULUS_OFFSET, 64);
            System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificate,
                    ProtocolConstants.CERT_EXPONENT_OFFSET, 3);
            adminCertificateBody = certificate;
        }
        return adminCertificateBody;
    }

    private byte[] adminMasterSignature() throws Exception {
        if (adminMasterSignature == null) {
            PrivateKey masterPrivateKey = loadPrivateKey(HARDWARE_KEY_DIRECTORY.resolve("master_private.pkcs8"));
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(masterPrivateKey);
            signature.update(adminCertificateBody());
            adminMasterSignature = requireSignatureLength(signature.sign());
        }
        return adminMasterSignature;
    }

    private byte[] signAdminOperation(byte[] operationData, byte[] nonce) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(adminKeyPair().getPrivate());
        signature.update(operationData);
        signature.update(nonce);
        return requireSignatureLength(signature.sign());
    }

    private KeyPair adminKeyPair() throws Exception {
        if (adminKeyPair != null) {
            return adminKeyPair;
        }

        Files.createDirectories(HARDWARE_KEY_DIRECTORY);
        Path privatePath = HARDWARE_KEY_DIRECTORY.resolve("admin_private.pkcs8");
        Path publicPath = HARDWARE_KEY_DIRECTORY.resolve("admin_public.x509");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            adminKeyPair = new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath))),
                    loadPrivateKey(privatePath));
            return adminKeyPair;
        }
        if (Files.exists(privatePath) || Files.exists(publicPath)) {
            throw new IllegalStateException("Incomplete admin key pair in " + HARDWARE_KEY_DIRECTORY);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(512);
        adminKeyPair = generator.generateKeyPair();
        Files.write(privatePath, adminKeyPair.getPrivate().getEncoded());
        Files.write(publicPath, adminKeyPair.getPublic().getEncoded());
        return adminKeyPair;
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing " + path
                    + ". Run the hardware gateway once or provide credentials matching the card.");
        }
        return KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
    }

    private static byte[] requireSignatureLength(byte[] signature) {
        if (signature.length != ProtocolConstants.SIGNATURE_LENGTH) {
            throw new IllegalStateException("Expected 64-byte RSA signature, got " + signature.length);
        }
        return signature;
    }

    private static byte[] toFixedByteArray(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] destination = new byte[length];
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, destination, length - copyLength, copyLength);
        return destination;
    }

    public void startProcess() {
        if (!connect()) {
            System.err.println("[AT] Reader not found, no card present, or applet SELECT failed.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        try {
            boolean running = true;
            while (running) {
                System.out.println("\n=== Admin Terminal Menu ===");
                System.out.println("1. Activate Member Card");
                System.out.println("2. Block Member Card");
                System.out.println("3. Exit");
                System.out.print("> ");

                if (!scanner.hasNextLine()) {
                    break;
                }
                String choice = scanner.nextLine().trim();
                switch (choice) {
                    case "1":
                        processActivate(scanner);
                        break;
                    case "2":
                        processBlock(scanner);
                        break;
                    case "3":
                        running = false;
                        break;
                    default:
                        System.out.println("Invalid choice.");
                }
            }
        } catch (Exception e) {
            System.err.println("[AT] Runtime Error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expiry date must use YYYYMMDD", e);
        }
    }

    public static void main(String[] args) throws Exception {
        AdminTerminal terminal = new AdminTerminal();
        terminal.loadMasterPublicKey("master_public.key");
        terminal.startProcess();
    }
}
