package terminals;

import backend.ApduDateCodec;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.math.BigInteger;
import java.nio.ByteBuffer;
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
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;

public class ControlledAccessTerminal extends BaseTerminal {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;

    private final RSAPrivateKey terminalPrivateKey;
    private final byte[] terminalCertificate;
    private byte[] masterSignature;

    public ControlledAccessTerminal() throws Exception {
        KeyPair keyPair = loadOrCreateTerminalKeyPair();
        terminalPrivateKey = (RSAPrivateKey) keyPair.getPrivate();
        terminalCertificate = buildTerminalCertificate((RSAPublicKey) keyPair.getPublic());
    }

    public void loadMasterSignature(String filePath) throws Exception {
        byte[] loaded = Files.readAllBytes(Paths.get(filePath));
        if (loaded.length != 64) {
            throw new IllegalArgumentException("Terminal master signature must be exactly 64 bytes");
        }
        masterSignature = loaded;
    }

    public void saveCertificateDataForSigning(String filePath) throws Exception {
        Files.write(Paths.get(filePath), terminalCertificate);
    }

    private void processCheckinTier2() throws Exception {
        if (masterSignature == null) {
            throw new IllegalStateException("A 64-byte master signature for this terminal certificate is required");
        }

        byte[] terminalNonce = new byte[16];
        SecureRandom.getInstanceStrong().nextBytes(terminalNonce);
        ResponseAPDU step1 = send(new CommandAPDU(CLA_PROPRIETARY, INS_T2_STEP1, 0x00, 0x00, terminalNonce));
        requireSuccess(step1, "Tier 2 step 1");
        if (step1.getData().length != 215) {
            throw new IllegalStateException("Tier 2 step 1 returned " + step1.getData().length
                    + " bytes; expected 215");
        }

        byte[] cardNonce = Arrays.copyOfRange(step1.getData(), 0, 16);
        byte[] cardSignature = Arrays.copyOfRange(step1.getData(), 16, 80);
        byte[] cardCertificate = Arrays.copyOfRange(step1.getData(), 80, 215);
        byte[] memberId = verifyAndGetIdFromCert(cardCertificate);
        PublicKey cardPublicKey = getCardPublicKeyFromCert(cardCertificate);

        Signature cardVerifier = Signature.getInstance("SHA1withRSA", "BC");
        cardVerifier.initVerify(cardPublicKey);
        cardVerifier.update(terminalNonce);
        if (!cardVerifier.verify(cardSignature)) {
            throw new SecurityException("Card Tier 2 signature verification failed");
        }

        byte[] currentDate = ApduDateCodec.encode(LocalDate.now());
        Signature signer = Signature.getInstance("SHA1withRSA", "BC");
        signer.initSign(terminalPrivateKey);
        signer.update(cardNonce);
        signer.update(currentDate);
        byte[] terminalSignature = signer.sign();

        ByteBuffer payload = ByteBuffer.allocate(203);
        payload.put(terminalSignature);
        payload.put(terminalCertificate);
        payload.put(masterSignature);
        payload.put(currentDate);

        ResponseAPDU step2 = send(new CommandAPDU(CLA_PROPRIETARY, INS_T2_STEP2, 0x00, 0x00, payload.array()));
        requireSuccess(step2, "Tier 2 step 2");
        if (step2.getData().length != 1) {
            throw new IllegalStateException("Tier 2 step 2 returned " + step2.getData().length
                    + " bytes; expected 1");
        }
        System.out.println("[CAT] ACCESS GRANTED for " + bytesToHex(memberId)
                + ". Daily Counter: " + (step2.getData()[0] & 0xFF));
    }

    private static byte[] buildTerminalCertificate(RSAPublicKey publicKey) {
        byte[] certificate = new byte[71];
        byte[] terminalId = {0x0A, 0x0B, 0x0C, 0x0D};
        System.arraycopy(terminalId, 0, certificate, 0, 4);
        System.arraycopy(toFixedByteArray(publicKey.getModulus(), 64), 0, certificate, 4, 64);
        System.arraycopy(toFixedByteArray(publicKey.getPublicExponent(), 3), 0, certificate, 68, 3);
        return certificate;
    }

    private static byte[] toFixedByteArray(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] destination = new byte[length];
        int sourceOffset = source.length > length ? source.length - length : 0;
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, sourceOffset, destination, length - copyLength, copyLength);
        return destination;
    }

    private static KeyPair loadOrCreateTerminalKeyPair() throws Exception {
        Path directory = Paths.get("hardware_keys");
        Path privatePath = directory.resolve("controlled_terminal_private.pkcs8");
        Path publicPath = directory.resolve("controlled_terminal_public.x509");
        Files.createDirectories(directory);

        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            KeyFactory factory = KeyFactory.getInstance("RSA", "BC");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            return new KeyPair(publicKey, privateKey);
        }
        if (Files.exists(privatePath) || Files.exists(publicPath)) {
            throw new IllegalStateException("Incomplete controlled-terminal key pair in hardware_keys");
        }

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(512);
        KeyPair keyPair = keyGen.generateKeyPair();
        Files.write(privatePath, keyPair.getPrivate().getEncoded());
        Files.write(publicPath, keyPair.getPublic().getEncoded());
        return keyPair;
    }

    private static void requireSuccess(ResponseAPDU response, String operation) {
        if (response.getSW() != 0x9000) {
            throw new IllegalStateException(operation + " failed with SW="
                    + String.format("%04X", response.getSW()));
        }
    }

    public void startProcess() {
        if (!connect()) {
            System.err.println("[CAT] Reader not found, no card present, or applet SELECT failed.");
            return;
        }
        try {
            processCheckinTier2();
        } catch (Exception e) {
            System.err.println("[CAT] Error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    public static void main(String[] args) throws Exception {
        ControlledAccessTerminal terminal = new ControlledAccessTerminal();
        if (args.length == 0) {
            terminal.saveCertificateDataForSigning("terminal_certificate_to_sign.bin");
            System.err.println("A master signature is required. Certificate data written to "
                    + "terminal_certificate_to_sign.bin.");
            System.err.println("Run again: ControlledAccessTerminal <terminal-master-signature.bin>");
            return;
        }
        terminal.loadMasterSignature(args[0]);
        terminal.loadMasterPublicKey("master_public.key");
        terminal.startProcess();
    }
}
