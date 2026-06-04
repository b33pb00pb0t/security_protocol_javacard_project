package terminals;

import backend.ApduDateCodec;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

public class AdminTerminal extends BaseTerminal {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private void syncWithDatabase(String memberId, String phone, String action) {
        System.out.println("[AT][DB-LOG] Action: " + action);
        System.out.println("[AT][DB-LOG] Member ID (Verified): " + memberId);
        System.out.println("[AT][DB-LOG] Linked Phone: " + (phone == null || phone.isEmpty() ? "N/A" : phone));
    }

    private void processActivate(Scanner scanner) throws Exception {
        byte[] memberId = verifyAndGetIdFromCert();
        String idHex = bytesToHex(memberId);
        System.out.println("[AT] Authentic Card Found: " + idHex);

        System.out.print("Enter Expiry Date (YYYYMMDD): ");
        LocalDate expiryDate = parseDate(scanner.nextLine());
        byte[] payload = new byte[12];
        System.arraycopy(memberId, 0, payload, 0, 4);
        System.arraycopy(ApduDateCodec.encode(LocalDate.now()), 0, payload, 4, 4);
        System.arraycopy(ApduDateCodec.encode(expiryDate), 0, payload, 8, 4);

        ResponseAPDU response = send(new CommandAPDU(CLA_PROPRIETARY, INS_ACTIVATE, 0x00, 0x00, payload));
        if (response.getSW() != 0x9000) {
            System.err.println("[AT] Activation failed. SW: " + String.format("%04X", response.getSW()));
            return;
        }

        // Phone remains backend-only and is never included in the card APDU.
        System.out.print("Enter Customer Phone Number for ID " + idHex + ": ");
        syncWithDatabase(idHex, scanner.nextLine().trim(), "ACTIVATION");
        System.out.println("[AT] Card activated successfully.");
    }

    private void processBlock() throws Exception {
        byte[] memberId = verifyAndGetIdFromCert();
        String idHex = bytesToHex(memberId);
        ResponseAPDU response = send(new CommandAPDU(CLA_PROPRIETARY, INS_BLOCK, 0x00, 0x00));
        if (response.getSW() != 0x9000) {
            System.err.println("[AT] Block failed. SW: " + String.format("%04X", response.getSW()));
            return;
        }
        syncWithDatabase(idHex, null, "BLOCK");
        System.out.println("[AT] Card blocked successfully.");
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
                        processBlock();
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
