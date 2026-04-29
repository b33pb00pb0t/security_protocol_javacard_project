package com.sports.simulator;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import javax.smartcardio.*;
import java.util.List;

public class MasterTerminal {

    //AID we choose to name the card
    private static final byte[] APPLET_AID = {
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, 
        (byte) 0x06, (byte) 0x07, (byte) 0x08, (byte) 0x09, (byte) 0x00
    };
    // gp --install MembershipApplet.cap --aid 01020304050607080900

    private static final byte CLA_PROPRIETARY = (byte) 0xB0;
    private static final byte INS_INITIALIZE = (byte) 0x10;

    public static void main(String[] args) {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) return;
            CardTerminal reader = terminals.get(0);
            Card card = reader.connect("*");
            CardChannel channel = card.getBasicChannel();

            System.out.println("Select the Applet...");
            CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID);
            ResponseAPDU res = channel.transmit(select);
            
            if (res.getSW() != 0x9000) {
                System.out.println("Selection error SW: " + Integer.toHexString(res.getSW()));
                return;
            }



            
            //data preparation
            byte[] memberID = { 0x00, 0x00, 0x00, 0x7B }; // ID 123
            byte[] initDate = { 0x20, 0x26, 0x04, 0x29 }; // Data: 2026-04-29
            String name = "Raul";
            byte[] nameBytes = name.getBytes();
            byte nameLen = (byte) nameBytes.length;

            //creating the payload
            // Length: 4 (ID) + 4 (Data) + 1 (name length) + N (name)
            byte[] payload = new byte[4 + 4 + 1 + nameBytes.length];

            //preparing the payload
            System.arraycopy(memberID, 0, payload, 0, 4);      // Offset 0: ID
            System.arraycopy(initDate, 0, payload, 4, 4);      // Offset 4: Data
            payload[8] = nameLen;                             // Offset 8: Length name
            System.arraycopy(nameBytes, 0, payload, 9, nameBytes.length); // Offset 9: The name itself

            //Sending the command
            System.out.println("Send command INITIALIZE (Member: " + name + ")...");
            CommandAPDU initCmd = new CommandAPDU(CLA_PROPRIETARY, INS_INITIALIZE, 0x00, 0x00, payload);
            ResponseAPDU initRes = channel.transmit(initCmd);







            if (initRes.getSW() == 0x9000) {
                System.out.println("INITIALIZE ompleted");
            } else if (initRes.getSW() == 0x6D00) {
                System.out.println("Error: INS not supported (Controlla il metodo process nell'Applet)");
            } else {
                System.out.println("Error SW: " + Integer.toHexString(initRes.getSW()));
            }

            card.disconnect(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}