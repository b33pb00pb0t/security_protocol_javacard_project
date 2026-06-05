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
    private static final byte INS_ADMIN_CHALLENGE = (byte) 0x30;
    private static final byte INS_GET_CERT = (byte) 0x60;
    private static final byte INS_GET_MEMBER_ID = (byte) 0x61;

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
    private short transactionCounter;
    private byte[] lastDate = new byte[4];
    private byte[] memberId = new byte[4];
    private byte[] expiryDate = new byte[4];
    private byte adminChallengeReady;

    private RSAPrivateKey cardPrivateKey;
    private RSAPublicKey masterPublicKey;
    private RSAPublicKey terminalPublicKey;

    private Signature signer;
    private Signature verifier;
    private RandomData rng;
    private final byte[] certC; 
    
    // Tier 2 challenge state must disappear when the card is deselected.
    private byte[] transientNt;
    private byte[] transientNc;
    private byte[] transientAuthData;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MembershipApplet().register();
    }

    private MembershipApplet() {
        currentState = STATE_INITIALIZE;
        dailyCounter = (byte) 0x00;
        transactionCounter = (short) 0;
        
        certC = new byte[ProtocolConstants.CARD_CERTIFICATE_LENGTH];
        
        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);
        terminalPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);

        // Reusing two Signature instances reduces persistent applet memory.
        signer = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        verifier = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

        transientNt = JCSystem.makeTransientByteArray(ProtocolConstants.NONCE_LENGTH, JCSystem.CLEAR_ON_DESELECT);
        transientNc = JCSystem.makeTransientByteArray(ProtocolConstants.NONCE_LENGTH, JCSystem.CLEAR_ON_DESELECT);
        transientAuthData = JCSystem.makeTransientByteArray((short) 64, JCSystem.CLEAR_ON_DESELECT);
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
            case INS_ADMIN_CHALLENGE: processAdminChallenge(apdu, buffer); break;
            case INS_GET_CERT: processGetCert(apdu, buffer); break;
            case INS_GET_MEMBER_ID: processGetMemberId(apdu, buffer); break;
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
        
        short bytesRead = receiveFullIncoming(apdu, buffer);
        if (bytesRead != ProtocolConstants.CARD_CERTIFICATE_LENGTH)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if (buffer[(short) (ISO7816.OFFSET_CDATA + ProtocolConstants.CERT_ROLE_OFFSET)]
                != ProtocolConstants.ROLE_CARD) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        verifier.init(masterPublicKey, Signature.MODE_VERIFY);
        if (!verifier.verify(buffer, ISO7816.OFFSET_CDATA,
                ProtocolConstants.CERTIFICATE_BODY_LENGTH,
                buffer,
                (short) (ISO7816.OFFSET_CDATA + ProtocolConstants.CERT_SIGNATURE_OFFSET),
                ProtocolConstants.SIGNATURE_LENGTH)) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try {
            Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, certC, (short) 0,
                    ProtocolConstants.CARD_CERTIFICATE_LENGTH);
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
        short bytesRead = receiveFullIncoming(apdu, buffer);
        if (bytesRead != ProtocolConstants.ADMIN_ACTIVATE_PAYLOAD_LENGTH)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        verifyAuthenticatedAdminCommand(buffer, ProtocolConstants.OP_ACTIVATE,
                ProtocolConstants.ADMIN_ACTIVATE_DATA_LENGTH);
        short dataOffset = ISO7816.OFFSET_CDATA;

        // If the card is removed mid-operation, the JCSystem rolls back to the previous state.
        JCSystem.beginTransaction();
        try {
            Util.arrayCopy(buffer, (short) (dataOffset + 1), memberId, (short) 0, (short) 4);
            Util.arrayCopy(buffer, (short)(dataOffset + 5), lastDate, (short) 0, (short) 4);
            Util.arrayCopy(buffer, (short)(dataOffset + 9), expiryDate, (short) 0, (short) 4);
            dailyCounter = (byte) 0x00;
            transactionCounter = (short) 0;
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

        short bytesRead = receiveFullIncoming(apdu, buffer);
        if (bytesRead != ProtocolConstants.ADMIN_BLOCK_PAYLOAD_LENGTH)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        verifyAuthenticatedAdminCommand(buffer, ProtocolConstants.OP_BLOCK,
                ProtocolConstants.ADMIN_BLOCK_DATA_LENGTH);
        verifySignedMemberId(buffer, (short) (ISO7816.OFFSET_CDATA + 1));
        
        JCSystem.beginTransaction();
        try {
            currentState = STATE_BLOCKED;
            JCSystem.commitTransaction();
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
    }

    private void processAdminChallenge(APDU apdu, byte[] buffer) {
        rng.generateData(transientNc, (short) 0, ProtocolConstants.NONCE_LENGTH);
        adminChallengeReady = (byte) 0x01;
        Util.arrayCopyNonAtomic(transientNc, (short) 0, buffer, (short) 0,
                ProtocolConstants.NONCE_LENGTH);
        apdu.setOutgoingAndSend((short) 0, ProtocolConstants.NONCE_LENGTH);
    }

    private void verifyAuthenticatedAdminCommand(byte[] buffer, byte operation, short operationDataLength) {
        if (adminChallengeReady != (byte) 0x01) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        adminChallengeReady = (byte) 0x00;

        short dataOffset = ISO7816.OFFSET_CDATA;
        short certOffset = (short) (dataOffset + operationDataLength);
        short masterSignatureOffset = (short) (certOffset + ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        short adminSignatureOffset = (short) (masterSignatureOffset + ProtocolConstants.SIGNATURE_LENGTH);

        if (buffer[dataOffset] != operation) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        boolean masterSignatureValid;
        try {
            verifier.init(masterPublicKey, Signature.MODE_VERIFY);
            masterSignatureValid = verifier.verify(buffer, certOffset,
                    ProtocolConstants.CERTIFICATE_BODY_LENGTH,
                    buffer, masterSignatureOffset, ProtocolConstants.SIGNATURE_LENGTH);
        } catch (Exception e) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
            return;
        }
        if (!masterSignatureValid
                || buffer[(short) (certOffset + ProtocolConstants.CERT_ROLE_OFFSET)]
                != ProtocolConstants.ROLE_ADMIN_TERMINAL) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        try {
            terminalPublicKey.setModulus(buffer,
                    (short) (certOffset + ProtocolConstants.CERT_MODULUS_OFFSET), (short) 64);
            terminalPublicKey.setExponent(buffer,
                    (short) (certOffset + ProtocolConstants.CERT_EXPONENT_OFFSET), (short) 3);
            Util.arrayCopyNonAtomic(buffer, dataOffset, transientAuthData, (short) 0,
                    operationDataLength);
            Util.arrayCopyNonAtomic(transientNc, (short) 0, transientAuthData,
                    operationDataLength, ProtocolConstants.NONCE_LENGTH);
            verifier.init(terminalPublicKey, Signature.MODE_VERIFY);
            if (!verifier.verify(transientAuthData, (short) 0,
                    (short) (operationDataLength + ProtocolConstants.NONCE_LENGTH),
                    buffer, adminSignatureOffset, ProtocolConstants.SIGNATURE_LENGTH)) {
                ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
        } catch (ISOException e) {
            throw e;
        } catch (Exception e) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
    }

    private void verifySignedMemberId(byte[] buffer, short memberIdOffset) {
        if (Util.arrayCompare(buffer, memberIdOffset, memberId, (short) 0,
                (short) 4) != (short) 0) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
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

        Util.arrayCopyNonAtomic(buffer, ISO7816.OFFSET_CDATA, transientNt, (short) 0,
                ProtocolConstants.NONCE_LENGTH);
        rng.generateData(transientNc, (short) 0, (short) 16);

        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 16);

        // Response: NC(16) || cardSignature(64) || Cert_C(136).
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

        // Payload: Sigma2(64) + Cert_T_Body(72) + Sigma1_MT(64) + Date(4)
        if (bytesRead != ProtocolConstants.TIER2_STEP2_PAYLOAD_LENGTH)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        short sigma2Offset = ISO7816.OFFSET_CDATA;
        short certTOffset = (short) (ISO7816.OFFSET_CDATA + 64);
        short sigma1Offset = (short) (certTOffset + ProtocolConstants.CERTIFICATE_BODY_LENGTH);
        short dateOffset = (short) (sigma1Offset + ProtocolConstants.SIGNATURE_LENGTH);

        // Verify the terminal certificate using the master public key, then load PK_T.
        boolean masterSignatureValid;
        try {
            verifier.init(masterPublicKey, Signature.MODE_VERIFY);
            masterSignatureValid = verifier.verify(buffer, certTOffset,
                    ProtocolConstants.CERTIFICATE_BODY_LENGTH,
                    buffer, sigma1Offset, ProtocolConstants.SIGNATURE_LENGTH);
        } catch (Exception e) {
            ISOException.throwIt(SW_MASTER_SIGNATURE_EXCEPTION);
            return;
        }
        if (!masterSignatureValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        if (buffer[(short) (certTOffset + ProtocolConstants.CERT_ROLE_OFFSET)]
                != ProtocolConstants.ROLE_CONTROLLED_ACCESS_TERMINAL) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        try {
            terminalPublicKey.setModulus(buffer,
                    (short) (certTOffset + ProtocolConstants.CERT_MODULUS_OFFSET), (short) 64);
            terminalPublicKey.setExponent(buffer,
                    (short) (certTOffset + ProtocolConstants.CERT_EXPONENT_OFFSET), (short) 3);
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
            transactionCounter++;
            JCSystem.commitTransaction();
        } catch (Exception e) {
            abortTransactionIfActive();
            ISOException.throwIt(SW_COUNTER_DATE_TRANSACTION_EXCEPTION);
        }

        short signedLength = buildTier2ReceiptData(buffer, certTOffset, dateOffset);
        buffer[0] = ProtocolConstants.RESULT_GRANTED;
        buffer[1] = dailyCounter;
        buffer[2] = (byte) (transactionCounter >> 8);
        buffer[3] = (byte) transactionCounter;
        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        signer.sign(transientAuthData, (short) 0, signedLength, buffer, (short) 4);
        apdu.setOutgoingAndSend((short) 0, ProtocolConstants.TIER2_RECEIPT_LENGTH);
    }

    private short buildTier2ReceiptData(byte[] buffer, short certTOffset, short dateOffset) {
        short offset = (short) 0;
        transientAuthData[offset++] = ProtocolConstants.OP_T2_RESULT;
        Util.arrayCopyNonAtomic(memberId, (short) 0, transientAuthData, offset, (short) 4);
        offset += (short) 4;
        Util.arrayCopyNonAtomic(buffer, (short) (certTOffset + ProtocolConstants.CERT_ID_OFFSET),
                transientAuthData, offset, (short) 4);
        offset += (short) 4;
        Util.arrayCopyNonAtomic(buffer, dateOffset, transientAuthData, offset, (short) 4);
        offset += (short) 4;
        Util.arrayCopyNonAtomic(transientNt, (short) 0, transientAuthData, offset,
                ProtocolConstants.NONCE_LENGTH);
        offset += ProtocolConstants.NONCE_LENGTH;
        Util.arrayCopyNonAtomic(transientNc, (short) 0, transientAuthData, offset,
                ProtocolConstants.NONCE_LENGTH);
        offset += ProtocolConstants.NONCE_LENGTH;
        transientAuthData[offset++] = dailyCounter;
        transientAuthData[offset++] = (byte) (transactionCounter >> 8);
        transientAuthData[offset++] = (byte) transactionCounter;
        transientAuthData[offset++] = ProtocolConstants.RESULT_GRANTED;
        return offset;
    }

    private void processGetCert(APDU apdu, byte[] buffer) {
        if (currentState == STATE_BLOCKED) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        Util.arrayCopy(certC, (short) 0, buffer, (short) 0, (short) certC.length);
        apdu.setOutgoingAndSend((short) 0, (short) certC.length);
    }

    private void processGetMemberId(APDU apdu, byte[] buffer) {
        if (currentState == STATE_INITIALIZE)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        Util.arrayCopy(memberId, (short) 0, buffer, (short) 0, (short) memberId.length);
        apdu.setOutgoingAndSend((short) 0, (short) memberId.length);
    }
}
