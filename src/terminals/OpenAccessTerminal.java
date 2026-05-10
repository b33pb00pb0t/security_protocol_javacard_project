package terminals;

import javax.smartcardio.*;
import java.util.Scanner;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * OpenAccess Terminal (OAT) - Tier 1
 * Handles general entry verification using offline PKI and 
 * a challenge-response protocol (6.1).
 */
public class OpenAccessTerminal extends BaseTerminal {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;
    private static final byte INS_GET_CERT = (byte) 0x60;

    /**
     * Executes the Tier 1 Check-in protocol.
     * Verifies the Card Certificate (Master Signature).
     * Checks the local Block List.
     *Challenges the card to prove possession of the Private Key.
     */
    private void processCheckin() throws Exception {
        System.out.println("\n[OAT] Starting Tier 1 Check-in...");

        try {
            //Retrieve and Verify Certificate using Master Public Key (SR2)
            // verifyAndGetIdFromCert() is inherited from BaseTerminal
            byte[] certData = send(new CommandAPDU(CLA_PROPRIETARY, INS_GET_CERT, 0x00, 0x00)).getData();
            byte[] memberId = verifyAndGetIdFromCert();
            
            String idHex = bytesToHex(memberId);
            System.out.println("[OAT] Card Authenticated. Member ID: " + idHex);

            //Check local Block List (Requirement 5.1)
            if (isIdBlocked(idHex)) {
                System.err.println("[OAT] ACCESS DENIED: Card is in the Block List.");
                return;
            }

            //Extract Card Public Key (PK_C) from Certificate
            // Structure: ID(4) + Modulus(64) + Exponent(3) + Signature(64)
            byte[] modulusBytes = new byte[64];
            byte[] exponentBytes = new byte[3];
            System.arraycopy(certData, 4, modulusBytes, 0, 64);
            System.arraycopy(certData, 68, exponentBytes, 0, 3);

            KeyFactory kf = KeyFactory.getInstance("RSA", "BC");
            RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(
                new BigInteger(1, modulusBytes),
                new BigInteger(1, exponentBytes)
            );
            PublicKey cardPublicKey = kf.generatePublic(pubSpec);

            //Generate 16-byte Nonce for Freshness (SR3)
            byte[] nonce = new byte[16];
            SecureRandom.getInstanceStrong().nextBytes(nonce);

            //Send Challenge (Nonce) to Card
            System.out.println("[OAT] Sending challenge to card...");
            ResponseAPDU sigRes = send(new CommandAPDU(CLA_PROPRIETARY, INS_CHECKIN_T1, 0x00, 0x00, nonce));

            if (sigRes.getSW() == 0x9000) {
                byte[] cardSignature = sigRes.getData();

                // 6. Verify Card Signature (SR1)
                Signature sig = Signature.getInstance("SHA1withRSA", "BC");
                sig.initVerify(cardPublicKey);
                sig.update(nonce);

                if (sig.verify(cardSignature)) {
                    System.out.println("[OAT] SUCCESS: Access Granted to " + idHex);
                    // Trigger physical gate opening here
                } else {
                    System.err.println("[OAT] FAILED: Digital signature mismatch.");
                }
            } else {
                System.err.println("[OAT] FAILED: Card rejected check-in. SW: " + Integer.toHexString(sigRes.getSW()));
            }

        } catch (SecurityException e) {
            System.err.println("[OAT] SECURITY ALERT: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[OAT] Error: " + e.getMessage());
        }
    }

    /**
     * Simulation of local block list check.
     */
    private boolean isIdBlocked(String idHex) {
        // In a real scenario, this checks a local file synced every 6 hours
        return false; 
    }

    public void startProcess() {
        if (!connect()) {
            System.err.println("[OAT] Failed to connect to card reader.");
            return;
        }

        try {
            System.out.println("=== OpenAccess Terminal (Tier 1) ===");
            System.out.println("Waiting for card...");
            
            // In a real terminal, this would be a loop waiting for card insertion
            processCheckin();

        } catch (Exception e) {
            System.err.println("[OAT] Runtime error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    public static void main(String[] args) {
        OpenAccessTerminal oat = new OpenAccessTerminal();
        try {
            // Load the master public key to enable certificate verification
            oat.loadMasterPublicKey("master_public.key");
            oat.startProcess();
        } catch (Exception e) {
            System.err.println("[OAT] Critical Error: " + e.getMessage());
        }
    }
}