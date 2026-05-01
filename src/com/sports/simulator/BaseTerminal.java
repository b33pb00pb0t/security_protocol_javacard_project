package com.sports.simulator;

import javax.smartcardio.*;
import java.util.List;

public abstract class BaseTerminal {
    // Queste sono le variabili di istanza che verranno usate da MasterTerminal
    protected Card card;
    protected CardChannel channel;

    protected static final byte[] APPLET_AID = {
        (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, 
        (byte) 0x06, (byte) 0x07, (byte) 0x08, (byte) 0x09, (byte) 0x00
    };

    public boolean connect() {
        try {
            TerminalFactory factory = TerminalFactory.getDefault();
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) return false;

            CardTerminal reader = terminals.get(0);
            
            this.card = reader.connect("*");
            this.channel = this.card.getBasicChannel();

            //Applet selection
            CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID);
            ResponseAPDU res = channel.transmit(select);
            return res.getSW() == 0x9000;
        } catch (CardException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void disconnect(boolean reset) {
        try {//Applet disconnection
            if (card != null) {
                card.disconnect(reset);
                System.out.println("Card disconnected.");
            }
        } catch (CardException e) {
            e.printStackTrace();
        }
    }

    protected ResponseAPDU send(CommandAPDU cmd) throws CardException {
        return channel.transmit(cmd);
    }
}