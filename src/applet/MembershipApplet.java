package applet;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.JCSystem;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

import javacard.security.KeyBuilder;
import javacard.security.RSAPrivateKey;
import javacard.security.RSAPublicKey;
import javacard.security.Signature;
import javacard.security.RandomData;

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

    private static final short SW_APDU_RECEIVE_PROBLEM = (short) 0x6F10;
    private static final short SW_TERMINAL_CERTIFICATE_PROBLEM = (short) 0x6F11;
    private static final short SW_MASTER_SIGNATURE_EXCEPTION = (short) 0x6F12;
    private static final short SW_TERMINAL_SIGNATURE_EXCEPTION = (short) 0x6F13;
    private static final short SW_COUNTER_DATE_TRANSACTION_EXCEPTION = (short) 0x6F14;

    private static final byte STATE_INITIALIZE = (byte) 0x00;
    private static final byte STATE_ACTIVE = (byte) 0x01;
    private static final byte STATE_INACTIVE = (byte) 0x02;
    private static final byte STATE_BLOCKED = (byte) 0x03;

    private byte currentState;
    private byte dailyCounter;
    private byte[] lastDate = new byte[4];
    private byte[] memberId = new byte[4];
    private byte[] expiryDate = new byte[4];

    private RSAPrivateKey cardPrivateKey;
    private RSAPublicKey masterPublicKey;
    private RSAPublicKey terminalPublicKey;

    private Signature signer;
    private Signature verifier;
    private RandomData rng;
    private final byte[] certC; 
    
    // Tier 2 challenge state must disappear when the card is deselected.
    private byte[] transientNc;
    private byte[] transientAuthData;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MembershipApplet().register();
    }

    private MembershipApplet() {
        currentState = STATE_INITIALIZE;
        dailyCounter = (byte) 0x00;
        
        certC = new byte[135];
        
        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);
        terminalPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);

        // Reusing two Signature instances reduces persistent applet memory.
        signer = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        verifier = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

        transientNc = JCSystem.makeTransientByteArray((short) 16, JCSystem.CLEAR_ON_DESELECT);
        transientAuthData = JCSystem.makeTransientByteArray((short) 20, JCSystem.CLEAR_ON_DESELECT);
    }   

    @Override
    public void process(APDU apdu) throws ISOException {
        if (selectingApplet()) return;

        byte[] buffer = apdu.getBuffer();
        if (buffer[ISO7816.OFFSET_CLA] != CLA_PROPRIETARY) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {
            case INS_INITIALIZE_KEY: processInitializeKey(apdu, buffer); break;
            case INS_LOAD_CERT: processLoadCert(apdu, buffer); break;
            case INS_LOAD_MASTER_KEY: processLoadMasterKey(apdu, buffer); break;
            case INS_ACTIVATE: processActivate(apdu, buffer); break;
            case INS_BLOCK: processBlock(apdu, buffer); break;
            case INS_CHECKIN_T1: processCheckInTier1(apdu, buffer); break;
            case INS_T2_STEP1: processCheckInTier2Step1(apdu); break;
            case INS_T2_STEP2: processCheckInTier2Step2(apdu); break;
            case INS_GET_CERT: processGetCert(apdu, buffer); break;
            default: ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // A physical JavaCard may deliver a short APDU in several receive chunks.
    private short receiveFullIncoming(APDU apdu, byte[] buffer) {
        short incomingLength = (short) (buffer[ISO7816.OFFSET_LC] & 0xFF);
        short totalReceived = (short) 0;
        short blockLength;
        try {
            blockLength = apdu.setIncomingAndReceive();
            totalReceived = blockLength;
            while (totalReceived < incomingLength) {
                blockLength = apdu.receiveBytes((short) (ISO7816.OFFSET_CDATA + totalReceived));
                if (blockLength <= (short) 0) {
                    break;
                }
                totalReceived += blockLength;
            }
        } catch (ISOException e) {
            throw e;
        } catch (Exception e) {
            ISOException.throwIt(SW_APDU_RECEIVE_PROBLEM);
        }

        if (totalReceived != incomingLength) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        return totalReceived;
    }

    private void abortTransactionIfActive() {
        try {
            if (JCSystem.getTransactionDepth() != (byte) 0) {
                JCSystem.abortTransaction();
            }
        } catch (Exception ignored) {
            // Preserve the original diagnostic status word.
        }
    }

    private void processInitializeKey(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 128) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try { 
            cardPrivateKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
            cardPrivateKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 64);
            
            JCSystem.commitTransaction();
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    private void processLoadCert(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 135) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try {
            Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, certC, (short) 0, (short) 135);
            JCSystem.commitTransaction(); 
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    private void processLoadMasterKey(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE)  
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 67) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        
        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try {
            masterPublicKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
            masterPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 3);
            
            JCSystem.commitTransaction();
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }
    
    private void processActivate(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE && currentState != STATE_INACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 12) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try {
            Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, memberId, (short) 0, (short) 4);
            Util.arrayCopy(buffer, (short)(ISO7816.OFFSET_CDATA + 4), lastDate, (short) 0, (short) 4);
            Util.arrayCopy(buffer, (short)(ISO7816.OFFSET_CDATA + 8), expiryDate, (short) 0, (short) 4);
            dailyCounter = (byte) 0x00;
            currentState = STATE_ACTIVE;
            JCSystem.commitTransaction(); 
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    private void processBlock(APDU apdu, byte[] buffer) {
        if (currentState != STATE_ACTIVE)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        JCSystem.beginTransaction();
        try {
            currentState = STATE_BLOCKED;
            JCSystem.commitTransaction();
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    private void processCheckInTier1(APDU apdu, byte[] buffer) {
        if (currentState != STATE_ACTIVE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 16) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }
   
    private void processCheckInTier2Step1(APDU apdu) {
        if (currentState != STATE_ACTIVE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();        
        if (bytesRead != (short) 16) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        rng.generateData(transientNc, (short) 0, (short) 16);

        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 16);

        // Response: NC(16) || cardSignature(64) || Cert_C(135).
        Util.arrayCopy(transientNc, (short) 0, buffer, (short) 0, (short) 16);
        short certOffset = (short) (16 + sigLen);
        Util.arrayCopy(certC, (short) 0, buffer, certOffset, (short) certC.length);
        short totalLength = (short) (certOffset + certC.length);
        apdu.setOutgoingAndSend((short) 0, totalLength);
    }

    private void processCheckInTier2Step2(APDU apdu) {
        if (currentState != STATE_ACTIVE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        byte[] buffer = apdu.getBuffer();
        short bytesRead = receiveFullIncoming(apdu, buffer);

        // Payload: Sigma2(64) + Cert_T(71) + Sigma1_MT(64) + Date(4)
        if (bytesRead != (short) 203)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short sigma2Offset = ISO7816.OFFSET_CDATA;
        short certTOffset = (short) (ISO7816.OFFSET_CDATA + 64);
        short sigma1Offset = (short) (ISO7816.OFFSET_CDATA + 135);
        short dateOffset = (short) (ISO7816.OFFSET_CDATA + 199);

        // Verify the terminal certificate using the master public key, then load PK_T.
        boolean masterSignatureValid;
        try {
            verifier.init(masterPublicKey, Signature.MODE_VERIFY);
            masterSignatureValid = verifier.verify(buffer, certTOffset, (short) 71,
                    buffer, sigma1Offset, (short) 64);
        } catch (Exception e) {
            ISOException.throwIt(SW_MASTER_SIGNATURE_EXCEPTION);
            return;
        }
        if (!masterSignatureValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        try {
            terminalPublicKey.setModulus(buffer, (short) (certTOffset + 4), (short) 64);
            terminalPublicKey.setExponent(buffer, (short) (certTOffset + 68), (short) 3);
        } catch (Exception e) {
            ISOException.throwIt(SW_TERMINAL_CERTIFICATE_PROBLEM);
            return;
        }

        // Verify Sigma2 over NC || Date, proving that the terminal owns SK_T.
        boolean terminalSignatureValid;
        try {
            Util.arrayCopyNonAtomic(transientNc, (short) 0, transientAuthData, (short) 0, (short) 16);
            Util.arrayCopyNonAtomic(buffer, dateOffset, transientAuthData, (short) 16, (short) 4);
            verifier.init(terminalPublicKey, Signature.MODE_VERIFY);
            terminalSignatureValid = verifier.verify(transientAuthData, (short) 0, (short) 20,
                    buffer, sigma2Offset, (short) 64);
        } catch (Exception e) {
            ISOException.throwIt(SW_TERMINAL_SIGNATURE_EXCEPTION);
            return;
        }
        if (!terminalSignatureValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        // Check subscription expiry. If today > expiryDate, the card becomes inactive.
        short expiryComparison;
        short dateCmp;
        try {
            expiryComparison = Util.arrayCompare(buffer, dateOffset, expiryDate, (short) 0, (short) 4);
            dateCmp = Util.arrayCompare(buffer, dateOffset, lastDate, (short) 0, (short) 4);
        } catch (Exception e) {
            ISOException.throwIt(SW_COUNTER_DATE_TRANSACTION_EXCEPTION);
            return;
        }
        if (expiryComparison > (short) 0) {
            try {
                JCSystem.beginTransaction();
                currentState = STATE_INACTIVE;
                JCSystem.commitTransaction();
            } catch (Exception e) {
                abortTransactionIfActive();
                ISOException.throwIt(SW_COUNTER_DATE_TRANSACTION_EXCEPTION);
            }
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        if (dateCmp == (short) 0 && (dailyCounter & 0xFF) >= 2) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Atomic counter update after the terminal is verified and the date is valid.
        try {
            JCSystem.beginTransaction();
            if (dateCmp != (short) 0) {
                Util.arrayCopy(buffer, dateOffset, lastDate, (short) 0, (short) 4);
                dailyCounter = (byte) 0x01;
            } else {
                dailyCounter++;
            }
            JCSystem.commitTransaction();
        } catch (Exception e) {
            abortTransactionIfActive();
            ISOException.throwIt(SW_COUNTER_DATE_TRANSACTION_EXCEPTION);
        }

        buffer[0] = dailyCounter;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    private void processGetCert(APDU apdu, byte[] buffer) {
        if (currentState == STATE_BLOCKED) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        Util.arrayCopy(certC, (short) 0, buffer, (short) 0, (short) certC.length);
        apdu.setOutgoingAndSend((short) 0, (short) certC.length);
    }
}
