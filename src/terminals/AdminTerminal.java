package terminals;

import javax.smartcardio.*;
import java.util.Scanner;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.nio.ByteBuffer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Administrator Terminal (AT)
 * Manages the card lifecycle: Activation and Blocking.
 * Security: Uses verifyAndGetIdFromCert() to ensure the Member ID is authentic.
 */
public class AdminTerminal extends BaseTerminal {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_GET_NONCE = (byte) 0x21;

    private RSAPrivateKey terminalPrivateKey;
    private byte[] terminalCertificate; // Cert_T: ID_T(4) + Mod(64) + Exp(3) = 71 bytes

    public AdminTerminal() {
        try {
            // Generate terminal-specific RSA keys for signing operations
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
            keyGen.initialize(512);
            KeyPair kp = keyGen.generateKeyPair();
            this.terminalPrivateKey = (RSAPrivateKey) kp.getPrivate();
            RSAPublicKey terminalPublicKey = (RSAPublicKey) kp.getPublic();

            // Prepare Cert_T (71 bytes) for Applet mutual authentication
            this.terminalCertificate = new byte[71];
            byte[] terminalId = {0x0A, 0x0B, 0x0C, 0x0E}; // Unique Admin ID
            byte[] modulus = extract64ByteModulus(terminalPublicKey);
            byte[] exponent = extract3ByteExponent(terminalPublicKey);

            System.arraycopy(terminalId, 0, terminalCertificate, 0, 4);
            System.arraycopy(modulus, 0, terminalCertificate, 4, 64);
            System.arraycopy(exponent, 0, terminalCertificate, 68, 3);

            System.out.println("[AT] Admin Terminal credentials initialized.");
        } catch (Exception e) {
            System.err.println("[AT] Initialization error: " + e.getMessage());
        }
    }

    /**
     * Placeholder for Backend Database Logic.
     * In a real system, this would involve a secure API call or SQL update.
     */
    private void syncWithDatabase(String memberId, String phone, String action) {
        // [PLACEHOLDER] Connect to your database (e.g., MySQL, Firebase, etc.)
        // Example: db.execute("UPDATE members SET status='ACTIVE', phone=? WHERE id=?", phone, memberId);
        System.out.println("[AT][DB-LOG] Action: " + action);
        System.out.println("[AT][DB-LOG] Member ID (Verified): " + memberId);
        System.out.println("[AT][DB-LOG] Linked Phone: " + (phone != null ? phone : "N/A"));
    }

    private void processActivate() throws Exception {
        System.out.println("[AT] Verifying card authenticity...");
        
        // 1. Get verified Member ID directly from the Card Certificate (SR2)
        byte[] memberId = verifyAndGetIdFromCert(); 
        String idHex = bytesToHex(memberId);
        System.out.println("[AT] Authentic Card Found: " + idHex);

        // 2. Freshness: Get nonce from card
        ResponseAPDU nonceRes = send(new CommandAPDU(CLA_PROPRIETARY, INS_GET_NONCE, 0x00, 0x00));
        if (nonceRes.getSW() != 0x9000) throw new CardException("Failed to get nonce");
        byte[] nonce = nonceRes.getData();

        // 3. Sign [MemberID + Nonce] for Mutual Authentication
        Signature sig = Signature.getInstance("SHA1withRSA", "BC");
        sig.initSign(terminalPrivateKey);
        sig.update(memberId);
        sig.update(nonce);
        byte[] sigma = sig.sign();

        // 4. Build 135-byte payload: Sigma(64) + Cert_T(71)
        ByteBuffer payload = ByteBuffer.allocate(135);
        payload.put(sigma);
        payload.put(terminalCertificate);

        // 5. Send Activation Command
        ResponseAPDU res = send(new CommandAPDU(CLA_PROPRIETARY, INS_ACTIVATE, 0x00, 0x00, payload.array()));

        if (res.getSW() == 0x9000) {
            System.out.println("[AT] Card activated successfully on card side.");
            
            // 6. Link to Phone Number (As per design document section 2.3.2)
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter Customer Phone Number for ID " + idHex + ": ");
            String phone = scanner.next();
            
            syncWithDatabase(idHex, phone, "ACTIVATION");
        } else {
            System.err.println("[AT] Activation failed. SW: " + Integer.toHexString(res.getSW()));
        }
    }

    private void processBlock() throws Exception {
        System.out.println("[AT] Verifying card authenticity for blocking...");
        
        // 1. Get verified Member ID (ensures we aren't blocking an arbitrary ID)
        byte[] memberId = verifyAndGetIdFromCert();
        String idHex = bytesToHex(memberId);
        System.out.println("[AT] Target Card ID: " + idHex);

        // 2. Freshness: Get nonce
        ResponseAPDU nonceRes = send(new CommandAPDU(CLA_PROPRIETARY, INS_GET_NONCE, 0x00, 0x00));
        byte[] nonce = nonceRes.getData();

        // 3. Generate signature
        Signature sig = Signature.getInstance("SHA1withRSA", "BC");
        sig.initSign(terminalPrivateKey);
        sig.update(memberId);
        sig.update(nonce);
        byte[] sigma = sig.sign();

        // 4. Build payload
        ByteBuffer payload = ByteBuffer.allocate(135);
        payload.put(sigma);
        payload.put(terminalCertificate);

        // 5. Send Block Command
        ResponseAPDU res = send(new CommandAPDU(CLA_PROPRIETARY, INS_BLOCK, 0x00, 0x00, payload.array()));

        if (res.getSW() == 0x9000) {
            System.out.println("[AT] Card blocked successfully.");
            syncWithDatabase(idHex, null, "BLOCK");
        } else {
            System.err.println("[AT] Block failed. SW: " + Integer.toHexString(res.getSW()));
        }
    }

    public void startProcess() {
        if (!connect()) {
            System.err.println("[AT] Reader not found or no card present.");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        try {
            while (running) {
                System.out.println("\n=== Admin Terminal Menu ===");
                System.out.println("1. Activate Member Card (Current)");
                System.out.println("2. Block Member Card (Current)");
                System.out.println("3. Exit");
                System.out.print("> ");

                if (!scanner.hasNextInt()) break;
                int choice = scanner.nextInt();

                switch (choice) {
                    case 1: processActivate(); break;
                    case 2: processBlock(); break;
                    case 3: running = false; break;
                    default: System.out.println("Invalid choice.");
                }
            }
        } catch (Exception e) {
            System.err.println("[AT] Runtime Error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    private byte[] extract64ByteModulus(RSAPublicKey key) {
        byte[] mod = key.getModulus().toByteArray();
        byte[] res = new byte[64];
        int start = (mod.length > 64) ? 1 : 0;
        System.arraycopy(mod, start, res, 0, 64);
        return res;
    }

    private byte[] extract3ByteExponent(RSAPublicKey key) {
        byte[] exp = key.getPublicExponent().toByteArray();
        byte[] res = new byte[3];
        int len = Math.min(exp.length, 3);
        System.arraycopy(exp, 0, res, 3 - len, len);
        return res;
    }

    public static void main(String[] args) throws Exception {
        AdminTerminal at = new AdminTerminal();
        at.loadMasterPublicKey("master_public.key");
        at.startProcess();
    }
}