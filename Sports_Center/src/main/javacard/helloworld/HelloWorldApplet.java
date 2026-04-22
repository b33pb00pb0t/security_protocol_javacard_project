package helloworld;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

public final class HelloWorldApplet extends Applet {

    private static final byte INS_HELLO = (byte) 0x01;
    private static final byte[] HELLO_MESSAGE = new byte[] {
        (byte) 'H', (byte) 'e', (byte) 'l', (byte) 'l', (byte) 'o',
        (byte) ' ',
        (byte) 'W', (byte) 'o', (byte) 'r', (byte) 'l', (byte) 'd'
    };

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new HelloWorldApplet().register(bArray, (short) (bOffset + 1), bArray[bOffset]);
    }

    private HelloWorldApplet() {
    }

    public void process(APDU apdu) throws ISOException {
        if (selectingApplet()) {
            return;
        }

        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != (byte) 0x00) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        if (buffer[ISO7816.OFFSET_INS] == INS_HELLO) {
            sendHello(apdu, buffer);
            return;
        }

        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
    }

    private void sendHello(APDU apdu, byte[] buffer) {
        short length = (short) HELLO_MESSAGE.length;
        Util.arrayCopyNonAtomic(HELLO_MESSAGE, (short) 0, buffer, (short) 0, length);
        apdu.setOutgoing();
        apdu.setOutgoingLength(length);
        apdu.sendBytes((short) 0, length);
    }
}
