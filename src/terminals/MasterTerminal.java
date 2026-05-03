package terminals;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import javax.smartcardio.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;

public class MasterTerminal extends BaseTerminal {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    
    /**
     * Operation 1: Provision the card's private key (RSA 512-bit)
     */
    private void initializeKey(KeyPair cardKeyPair) throws CardException {
        RSAPrivateKey privateKey = (RSAPrivateKey) cardKeyPair.getPrivate();
        byte[] payload = new byte[128];
        
        // 64 bytes Modulus + 64 bytes Private Exponent
        System.arraycopy(toFixedByteArray(privateKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(privateKey.getPrivateExponent(), 64), 0, payload, 64, 64);

        CommandAPDU cmd = new CommandAPDU(CLA_PROPRIETARY, INS_INITIALIZE_KEY, 0x00, 0x00, payload);
        ResponseAPDU res = channel.transmit(cmd);
        
        handleResponse(res, "Private Key Initialization");
    }

    /**
     * Operation 2: Load the Master Public Key onto the card
     */
    private void loadMasterKey(RSAPublicKey masterPublicKey) throws CardException {
        byte[] payload = new byte[67];
        
        // 64 bytes Modulus + 3 bytes Exponent (usually 65537)
        System.arraycopy(toFixedByteArray(masterPublicKey.getModulus(), 64), 0, payload, 0, 64);
        System.arraycopy(toFixedByteArray(masterPublicKey.getPublicExponent(), 3), 0, payload, 64, 3);

        CommandAPDU cmd = new CommandAPDU(CLA_PROPRIETARY, INS_LOAD_MASTER_KEY, 0x00, 0x00, payload);
        ResponseAPDU res = channel.transmit(cmd);

        handleResponse(res, "Master Key Loading");
    }

    /**
     * Operation 3: Load the Master-signed certificate onto the card
     */
    private void loadCertificate(byte[] cardId, RSAPublicKey cardPubKey) throws CardException {
        // CertC Structure: ID_C (4) + PK_C Mod (64) + PK_C Exp (3) + Signature (64) = 135 bytes total
        byte[] certData = new byte[135];
        
        System.arraycopy(cardId, 0, certData, 0, 4);
        System.arraycopy(toFixedByteArray(cardPubKey.getModulus(), 64), 0, certData, 4, 64);
        System.arraycopy(toFixedByteArray(cardPubKey.getPublicExponent(), 3), 0, certData, 68, 3);
        
        // Placeholder for the actual signature. In a production environment, 
        // this would be the RSA signature of the first 71 bytes using the Master Private Key.
        byte[] dummySignature = new byte[64];
        System.arraycopy(dummySignature, 0, certData, 71, 64);

        CommandAPDU cmd = new CommandAPDU(CLA_PROPRIETARY, INS_LOAD_CERT, 0x00, 0x00, certData);
        ResponseAPDU res = channel.transmit(cmd);

        handleResponse(res, "Certificate Loading");
    }

    //UTILITIES 
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
                
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
                keyGen.initialize(512);
                KeyPair masterKeyPair = keyGen.generateKeyPair();
                KeyPair cardKeyPair = keyGen.generateKeyPair();

                // Ora puoi chiamare i metodi in sicurezza
                initializeKey(cardKeyPair);
                loadMasterKey((RSAPublicKey) masterKeyPair.getPublic());
                
                byte[] cardId = {0x00, 0x00, 0x00, 0x01};
                loadCertificate(cardId, (RSAPublicKey) cardKeyPair.getPublic());

            } catch (CardException e) {
                System.err.println("[MT] Communication error with the card: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[MT] General error: " + e.getMessage());
            } finally {
                disconnect(false);
            }
        }

    public static void main(String[] args) {
        MasterTerminal mt = new MasterTerminal();        
        mt.startProcess();
    }
}