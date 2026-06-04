package terminals;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;

/**
 * Standalone Tier 1 APDU demo. The GUI access terminal is the complete path
 * that combines card authentication with the synced backend policy snapshot.
 */
public class OpenAccessTerminal extends BaseTerminal {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;

    private void processCheckin() throws Exception {
        System.out.println("\n[OAT] Starting Tier 1 Check-in...");

        try {
            byte[] certData = getCardCertificate();
            byte[] memberId = verifyAndGetIdFromCert(certData);
            
            String idHex = bytesToHex(memberId);
            System.out.println("[OAT] Card Authenticated. Member ID: " + idHex);

            if (isIdBlocked(idHex)) {
                System.err.println("[OAT] ACCESS DENIED: Card is in the Block List.");
                return;
            }

            PublicKey cardPublicKey = getCardPublicKeyFromCert(certData);

            byte[] nonce = new byte[16];
            SecureRandom.getInstanceStrong().nextBytes(nonce);

            System.out.println("[OAT] Sending challenge to card...");
            ResponseAPDU sigRes = send(new CommandAPDU(CLA_PROPRIETARY, INS_CHECKIN_T1, 0x00, 0x00, nonce));

            if (sigRes.getSW() == 0x9000) {
                byte[] cardSignature = sigRes.getData();

                Signature sig = Signature.getInstance("SHA1withRSA", "BC");
                sig.initVerify(cardPublicKey);
                sig.update(nonce);

                if (sig.verify(cardSignature)) {
                    System.out.println("[OAT] SUCCESS: Access Granted to " + idHex);
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

    private boolean isIdBlocked(String idHex) {
        // This standalone APDU demo has no backend snapshot; use the GUI for policy checks.
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
            oat.loadMasterPublicKey("master_public.key");
            oat.startProcess();
        } catch (Exception e) {
            System.err.println("[OAT] Critical Error: " + e.getMessage());
        }
    }
}
