package terminals;

import javax.smartcardio.*;
import java.util.Scanner;

public class AdminTerminal extends BaseTerminal {
    
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;

    /**
     * Operation: Activate the card
     */
    private void processActivate() throws CardException {
        // Esempio dati: ID 1234 (00 00 04 D2) e Data 2026-05-03 (20 26 05 03)
        byte[] memberId = {0x00, 0x00, 0x04, (byte)0xD2}; 
        byte[] currentDate = {0x20, 0x26, 0x05, 0x03};
        
        byte[] payload = new byte[8];
        System.arraycopy(memberId, 0, payload, 0, 4);
        System.arraycopy(currentDate, 0, payload, 4, 4);

        System.out.println("[AT] Sending ACTIVATE command...");
        CommandAPDU cmd = new CommandAPDU(CLA_PROPRIETARY, INS_ACTIVATE, 0x00, 0x00, payload);
        ResponseAPDU res = channel.transmit(cmd);
        
        handleResponse(res, "Activation card");
    }

    /**
     * Operation: Block the card
     */
    private void processBlock() throws CardException {
        System.out.println("[AT] Sending BLOCK command...");
        // Supponendo che il blocco non richieda dati extra (payload vuoto)
        CommandAPDU cmd = new CommandAPDU(CLA_PROPRIETARY, INS_BLOCK, 0x00, 0x00);
        ResponseAPDU res = channel.transmit(cmd);
        
        handleResponse(res, "Blocking card");
    }

    private void handleResponse(ResponseAPDU res, String opName) {
        if (res.getSW() == 0x9000) {
            System.out.println("[AT] " + opName + " successful.");
        } else {
            System.err.println("[AT] " + opName + " failed. SW: " + Integer.toHexString(res.getSW()));
        }
    }

    public void startProcess() {
        if (!connect()) {
            System.err.println("[AT] Failed to connect to card.");
            return;
        }

        try {
            System.out.println("Connection OK! (Admin Terminal)");
            Scanner scanner = new Scanner(System.in);
            //close the scanner??
            
            System.out.println("Select operation:");
            System.out.println("1. Activate Card");
            System.out.println("2. Block Card");
            System.out.print("> ");
            
            if (scanner.hasNextInt()) {
            int choice = scanner.nextInt();

                if (choice == 1) {
                    processActivate();
                } else if (choice == 2) {
                    processBlock();
                } else {
                    System.out.println("Invalid choice.");
                }
            }

        } catch (CardException e) {
            System.err.println("[AT] Communication error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[AT] General error: " + e.getMessage());
        } finally {
            disconnect(false);
        }
    }

    public static void main(String[] args) {
        AdminTerminal at = new AdminTerminal();        
        at.startProcess();
    }
}