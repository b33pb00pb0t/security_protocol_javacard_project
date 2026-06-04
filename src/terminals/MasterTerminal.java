package terminals;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.io.FileOutputStream;
import java.math.BigInteger;

public class MasterTerminal extends BaseTerminal {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    
    private KeyPair masterKeyPair;

    public MasterTerminal() throws NoSuchAlgorithmException, NoSuchProviderException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(512);
        this.masterKeyPair = keyGen.generateKeyPair();
    }

    public void saveMasterPublicKey(String filePath) throws Exception {
        byte[] pubKeyBytes = masterKeyPair.getPublic().getEncoded();
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(pubKeyBytes);
        }
        System.out.println("[MT] Master Public Key saved to: " + filePath);
    }

    private void initializeKey(KeyPair cardKeyPair) throws CardException {
        RSAPrivateKey privateKey = (RSAPrivateKey) cardKeyPair.getPrivate();
        byte[] payload = new byte[128];

        System.arraycopy(toFixedByteArray(privateKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(privateKey.getPrivateExponent(), 64), 0, payload, 64, 64);

        ResponseAPDU res = send(new CommandAPDU(CLA_PROPRIETARY, INS_INITIALIZE_KEY, 0x00, 0x00, payload));
        handleResponse(res, "Private Key Initialization");
    }

    private void loadMasterKey() throws CardException {
        RSAPublicKey masterPublicKey = (RSAPublicKey) masterKeyPair.getPublic();

        byte[] payload = new byte[67];
        System.arraycopy(toFixedByteArray(masterPublicKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(masterPublicKey.getPublicExponent(), 3), 0, payload, 64, 3);

        ResponseAPDU res = send(new CommandAPDU(CLA_PROPRIETARY, INS_LOAD_MASTER_KEY, 0x00, 0x00, payload));
        handleResponse(res, "Master Key Loading");
    }

    private void loadCertificate(byte[] cardId, RSAPublicKey cardPubKey)
            throws Exception {

        final int ID_LEN = 4;
        final int MOD_LEN = 64;
        final int EXP_LEN = 3;
        final int SIG_LEN = 64;
        final int DATA_LEN = 71;
        final int CERT_LEN = 135;

        byte[] certData = new byte[CERT_LEN];

        System.arraycopy(cardId, 0, certData, 0, ID_LEN);
        System.arraycopy(toFixedByteArray(cardPubKey.getModulus(), MOD_LEN), 0, certData, 4, MOD_LEN);
        System.arraycopy(toFixedByteArray(cardPubKey.getPublicExponent(), EXP_LEN), 0, certData, 68, EXP_LEN);

        Signature sig = Signature.getInstance("SHA1withRSA", "BC");
        sig.initSign(masterKeyPair.getPrivate());
        sig.update(certData, 0, DATA_LEN);
        byte[] signature = sig.sign();

        if (signature.length != SIG_LEN) {
            throw new IllegalStateException("Invalid signature length: " + signature.length);
        }

        Signature verifySig = Signature.getInstance("SHA1withRSA", "BC");
        verifySig.initVerify(masterKeyPair.getPublic());
        verifySig.update(certData, 0, DATA_LEN);

        if (!verifySig.verify(signature)) {
            throw new RuntimeException("Signature verification failed");
        }

        System.arraycopy(signature, 0, certData, DATA_LEN, SIG_LEN);

        ResponseAPDU res = send(new CommandAPDU(CLA_PROPRIETARY, INS_LOAD_CERT, 0x00, 0x00, certData));
        handleResponse(res, "Certificate Loading");
    }
    private static byte[] toFixedByteArray(BigInteger val, int len) {
        byte[] src = val.toByteArray();
        byte[] dest = new byte[len];
        int startSrc = (src.length > len) ? src.length - len : 0;
        int lenToCopy = Math.min(src.length, len);
        System.arraycopy(src, startSrc, dest, len - lenToCopy, lenToCopy);
        return dest;
    }

    private void handleResponse(ResponseAPDU res, String opName) {
        if (res.getSW() == 0x9000) {
            System.out.println("[MT] " + opName + " successful.");
        } else {
            System.err.println("[MT] " + opName + " failed. SW: " + Integer.toHexString(res.getSW()));
        }
    }
    public void startProcess() {
        if (!connect()) {
            System.err.println("[MT] Failed to connect to card.");
            return;
        }

        try {
            System.out.println("Connection OK! (Master Terminal)");
            saveMasterPublicKey("master_public.key");

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
            keyGen.initialize(512);

            KeyPair cardKeyPair = keyGen.generateKeyPair();

            initializeKey(cardKeyPair);
            loadMasterKey();

            byte[] cardId = {0x00, 0x00, 0x00, 0x01};
            loadCertificate(cardId, (RSAPublicKey) cardKeyPair.getPublic());

        } catch (Exception e) {
            System.err.println("[MT] Error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    public static void main(String[] args) throws Exception {
        MasterTerminal mt = new MasterTerminal();
        mt.startProcess();
    }
}
