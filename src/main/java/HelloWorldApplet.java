package helloworld;

import javacard.framework.*;

/**
 * A simple Hello World applet for demonstrating basic APDU communication
 */
public class HelloWorldApplet extends Applet {
    
    // Instruction codes
    private static final byte INS_HELLO_WORLD = (byte) 0x01;
    
    // Message to send
    private static final byte[] HELLO_MESSAGE = {
        (byte) 0x48, (byte) 0x65, (byte) 0x6C, (byte) 0x6C, (byte) 0x6F, // "Hello"
        (byte) 0x20,                                                       // " "
        (byte) 0x57, (byte) 0x6F, (byte) 0x72, (byte) 0x6C, (byte) 0x64  // "World"
    };
    
    /**
     * Installs this applet
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new HelloWorldApplet().register(bArray, (short) (bOffset + 1), bArray[bOffset]);
    }
    
    /**
     * Processes incoming APDU commands
     */
    public void process(APDU apdu) throws ISOException {
        byte[] buffer = apdu.getBuffer();
        
        // Check for SELECT command (CLA=0x00, INS=0xA4)
        if ((buffer[ISO7816.OFFSET_CLA] == 0x00) && 
            (buffer[ISO7816.OFFSET_INS] == (byte) 0xA4)) {
            return;  // Applet selected successfully
        }
        
        // Check if CLA is correct (0x00)
        if (buffer[ISO7816.OFFSET_CLA] != 0x00) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
        
        // Handle HELLO_WORLD instruction
        if (buffer[ISO7816.OFFSET_INS] == INS_HELLO_WORLD) {
            handleHelloWorld(apdu);
            return;
        }
        
        // Unknown instruction
        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
    }
    
    /**
     * Handles the HELLO_WORLD APDU command
     */
    private void handleHelloWorld(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short len = (short) HELLO_MESSAGE.length;
        
        // Copy message to response
        Util.arrayCopy(HELLO_MESSAGE, (short) 0, buffer, (short) 0, len);
        
        // Send response (message length bytes)
        apdu.setOutgoing();
        apdu.setOutgoingLength(len);
        apdu.sendBytes((short) 0, len);
    }
}
