package applet;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.KeyBuilder;
import javacard.security.RSAPrivateKey;
import javacard.security.RSAPublicKey;
import javacard.security.RandomData;
import javacard.security.Signature;

public final class MembershipApplet extends Applet {
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;

    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;
    private static final byte INS_GET_CERT = (byte) 0x60;

    private static final byte STATE_INITIALIZE = (byte) 0x00;
    private static final byte STATE_ACTIVE = (byte) 0x01;
    private static final byte STATE_INACTIVE = (byte) 0x02;
    private static final byte STATE_BLOCKED = (byte) 0x03;

    private byte currentState;
    private byte dailyCounter;
    private final byte[] lastDate;
    private final byte[] memberId;
    private final byte[] certC;

    private final RSAPrivateKey cardPrivateKey;
    private final RSAPublicKey masterPublicKey;
    private final RSAPublicKey terminalPublicKey;
    private final Signature rsaSignature;
    private final Signature certVerifySignature;
    private final Signature rsaVerify;
    private final RandomData rng;
    private final byte[] transientNc;
    private final byte[] transientCertSig;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MembershipApplet().register();
    }

    private MembershipApplet() {
        currentState = STATE_INITIALIZE;
        dailyCounter = (byte) 0x00;
        lastDate = new byte[4];
        memberId = new byte[4];
        certC = new byte[135];

        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE,
                KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC,
                KeyBuilder.LENGTH_RSA_512, false);
        terminalPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC,
                KeyBuilder.LENGTH_RSA_512, false);

        rsaSignature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        certVerifySignature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        rsaVerify = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        transientNc = JCSystem.makeTransientByteArray((short) 16, JCSystem.CLEAR_ON_DESELECT);
        transientCertSig = JCSystem.makeTransientByteArray((short) 64, JCSystem.CLEAR_ON_DESELECT);
    }

    @Override
    public void process(APDU apdu) throws ISOException {
        if (selectingApplet()) {
            return;
        }

        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != CLA_PROPRIETARY) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_INITIALIZE_KEY:
                processInitializeKey(apdu, buffer);
                return;
            case INS_LOAD_CERT:
                processLoadCert(apdu, buffer);
                return;
            case INS_LOAD_MASTER_KEY:
                processLoadMasterKey(apdu, buffer);
                return;
            case INS_ACTIVATE:
                processActivate(apdu, buffer);
                return;
            case INS_BLOCK:
                processBlock();
                return;
            case INS_CHECKIN_T1:
                processCheckInTier1(apdu, buffer);
                return;
            case INS_T2_STEP1:
                processCheckInTier2Step1(apdu);
                return;
            case INS_T2_STEP2:
                processCheckInTier2Step2(apdu);
                return;
            case INS_GET_CERT:
                processGetCert(apdu);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    private void processInitializeKey(APDU apdu, byte[] buffer) {
        requireInitializeState();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 128) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        cardPrivateKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
        cardPrivateKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 64);
    }

    private void processLoadCert(APDU apdu, byte[] buffer) {
        requireInitializeState();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 135) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, certC, (short) 0, (short) 135);
    }

    private void processLoadMasterKey(APDU apdu, byte[] buffer) {
        requireInitializeState();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 67) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        masterPublicKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
        masterPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 3);
    }

    private void processActivate(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE && currentState != STATE_INACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 8) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, memberId, (short) 0, (short) 4);
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 4), lastDate, (short) 0, (short) 4);
        dailyCounter = (byte) 0x00;
        currentState = STATE_ACTIVE;
    }

    private void processBlock() {
        if (currentState == STATE_BLOCKED) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        currentState = STATE_BLOCKED;
    }

    private void processCheckInTier1(APDU apdu, byte[] buffer) {
        requireActiveState();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 16) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        rsaSignature.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    private void processCheckInTier2Step1(APDU apdu) {
        requireActiveState();
        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 16) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        rng.generateData(transientNc, (short) 0, (short) 16);
        rsaSignature.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16,
                buffer, (short) (ISO7816.OFFSET_CDATA + 16));

        Util.arrayCopy(transientNc, (short) 0, buffer, (short) 0, (short) 16);
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 16), buffer, (short) 16, sigLen);
        Util.arrayCopy(certC, (short) 0, buffer, (short) 80, (short) 135);
        apdu.setOutgoingAndSend((short) 0, (short) 215);
    }

    private void processCheckInTier2Step2(APDU apdu) {
        requireActiveState();
        byte[] buffer = apdu.getBuffer();
        short totalLength = apdu.getIncomingLength();
        short bytesRead = apdu.setIncomingAndReceive();
        while (bytesRead < totalLength) {
            bytesRead += apdu.receiveBytes((short) (ISO7816.OFFSET_CDATA + bytesRead));
        }
        if (bytesRead != (short) 203) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 135),
                transientCertSig, (short) 0, (short) 64);

        certVerifySignature.init(masterPublicKey, Signature.MODE_VERIFY);
        boolean certValid = certVerifySignature.verify(buffer, (short) (ISO7816.OFFSET_CDATA + 64),
                (short) 71, transientCertSig, (short) 0, (short) 64);
        if (!certValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        terminalPublicKey.setModulus(buffer, (short) (ISO7816.OFFSET_CDATA + 68), (short) 64);
        terminalPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 132), (short) 3);

        rsaVerify.init(terminalPublicKey, Signature.MODE_VERIFY);
        rsaVerify.update(transientNc, (short) 0, (short) 16);
        boolean sigValid = rsaVerify.verify(buffer, (short) (ISO7816.OFFSET_CDATA + 199),
                (short) 4, buffer, ISO7816.OFFSET_CDATA, (short) 64);
        if (!sigValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        Util.arrayFillNonAtomic(transientNc, (short) 0, (short) 16, (byte) 0);

        short dateCmp = Util.arrayCompare(buffer, (short) (ISO7816.OFFSET_CDATA + 199),
                lastDate, (short) 0, (short) 4);
        if (dateCmp != (short) 0) {
            Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 199), lastDate, (short) 0, (short) 4);
            dailyCounter = (byte) 0x01;
        } else {
            if ((dailyCounter & 0xFF) >= 2) {
                ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
            }
            dailyCounter++;
        }

        buffer[0] = dailyCounter;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    private void processGetCert(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopy(certC, (short) 0, buffer, (short) 0, (short) 135);
        apdu.setOutgoingAndSend((short) 0, (short) 135);
    }

    private void requireInitializeState() {
        if (currentState != STATE_INITIALIZE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
    }

    private void requireActiveState() {
        if (currentState != STATE_ACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
    }
}
