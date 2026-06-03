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

    // Proprietary CLA for the Sports Recreation Center membership protocol
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;

    // APDU instruction bytes for the membership lifecycle and check-in flow
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10;
    private static final byte INS_LOAD_CERT = (byte) 0x11;
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12;
    private static final byte INS_ACTIVATE = (byte) 0x13;
    private static final byte INS_BLOCK = (byte) 0x14;
    private static final byte INS_CHECKIN_T1 = (byte) 0x20;
    private static final byte INS_T2_STEP1 = (byte) 0x21;
    private static final byte INS_T2_STEP2 = (byte) 0x22;
    private static final byte INS_GET_CERT = (byte) 0x60;

    // Lifecycle state constants
    private static final byte STATE_INITIALIZE = (byte) 0x00;
    private static final byte STATE_ACTIVE = (byte) 0x01;
    private static final byte STATE_INACTIVE = (byte) 0x02;
    private static final byte STATE_BLOCKED = (byte) 0x03;

    // Persistent data fields
    private byte currentState; // Current lifecycle state of the card
    private byte dailyCounter; // Counts check-ins per day
    private byte[] lastDate = new byte[4]; // Last date a check-in occurred
    private byte[] memberId = new byte[4]; // Unique membership ID
    private byte[] expiryDate = new byte[4]; // Expiration date of the membership

    // Security keys
    private RSAPrivateKey cardPrivateKey; // Card's private RSA key
    private RSAPublicKey masterPublicKey; // Master Terminal's public key
    private RSAPublicKey terminalPublicKey; // Current Terminal's public key

    // Signature objects (Consolidated to save RAM/EEPROM)
    private Signature signer; // Used for signing challenges
    private Signature verifier; // Used for verifying signatures

    // Random number generator for challenges
    private RandomData rng;

    // Card certificate buffer
    private final byte[] certC; 
    
    // Transient buffers for session data
    private byte[] transientNc; // Stores nonces temporarily
    private byte[] transientCertSig; 

    /*
     * The Terminal sends a request.
     * The card generates a random number (rng) and stores it in transientNc.
     * The card signs the nonce (signer) using cardPrivateKey.
     * The terminal responds by providing a signature (verifier using terminalPublicKey).
     * The card compares the command date with lastDate.
     * If everything is OK, it updates dailyCounter and saves the new date in lastDate (all atomically, as we saw before).
     */

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new MembershipApplet().register();
    }

    private MembershipApplet() {
        currentState = STATE_INITIALIZE;
        dailyCounter = (byte) 0x00;
        
        // Allocate space for certificate (135 bytes)
        certC = new byte[135];
        
        // Build RSA keys (using 512-bit for resource-constrained JavaCard examples)
        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);
        terminalPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);

        // Initialize consolidated signature objects
        signer = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        verifier = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        
        // Secure random data for nonces
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

        // Transient arrays in RAM (automatically cleared on deselect)
        transientNc = JCSystem.makeTransientByteArray((short) 16, JCSystem.CLEAR_ON_DESELECT);
        transientCertSig = JCSystem.makeTransientByteArray((short) 64, JCSystem.CLEAR_ON_DESELECT);
    }   

    @Override
    public void process(APDU apdu) throws ISOException {
        if (selectingApplet()) return;

        byte[] buffer = apdu.getBuffer();
        // Check CLA byte
        if (buffer[ISO7816.OFFSET_CLA] != CLA_PROPRIETARY) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        // Handle APDU instructions
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

    /* 
    private void processInitializeKey(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 128) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Provision card private key
        cardPrivateKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
        cardPrivateKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 64);
    }*/
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

    /* 
    private void processLoadCert(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 135) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, certC, (short) 0, (short) 135);
    }*/
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

    /* 
    private void processLoadMasterKey(APDU apdu, byte[] buffer) {
        if (currentState != STATE_INITIALIZE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 67) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        
        masterPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 3);
        masterPublicKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
    }*/
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

    /* 
    private void processBlock(APDU apdu, byte[] buffer) {
        if (currentState == STATE_BLOCKED) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        currentState = STATE_BLOCKED;
    }
    */
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

        // Sign challenge using card's private key
        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 0);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }
   
    /* 
    private void processCheckInTier2Step1(APDU apdu) {
        if (currentState != STATE_ACTIVE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();
        if (bytesRead != (short) 16) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // Generate challenge for terminal
        rng.generateData(transientNc, (short) 0, (short) 16);
        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) (ISO7816.OFFSET_CDATA + 16));

        // Prepare response packet for terminal
        Util.arrayCopy(transientNc, (short) 0, buffer, (short) 0, (short) 16);
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 16), buffer, (short) 16, sigLen);
        Util.arrayCopy(certC, (short) 0, buffer, (short) 80, (short) 135);
        apdu.setOutgoingAndSend((short) 0, (short) 215);
    }*/
    private void processCheckInTier2Step1(APDU apdu) {
        if (currentState != STATE_ACTIVE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();        
        if (bytesRead != (short) 16) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // 1. Generate challenge for the terminal (NC), ensure transientNc is initialized as CLEAR_ON_DESELECT in the constructor
        rng.generateData(transientNc, (short) 0, (short) 16);

        // 2. Sign the terminal's challenge (16 bytes input), the signature starts at index 16 (immediately after the NC challenge)
        signer.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = signer.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 16);

        // 3. Assemble the response packet: [NC (16 bytes)] + [Signature (sigLen)] + [Certificate (135 bytes)]
        // Copy the generated challenge (transientNc) to the start of the buffer
        Util.arrayCopy(transientNc, (short) 0, buffer, (short) 0, (short) 16);

        // Calculate where the certificate starts: 16 (NC) + sigLen (Dynamic signature length)
        short certOffset = (short) (16 + sigLen);
        
        // Copy the certificate into the buffer at the calculated position
        Util.arrayCopy(certC, (short) 0, buffer, certOffset, (short) certC.length);

        // Calculate total length: 16 + sigLen + 135
        short totalLength = (short) (certOffset + certC.length);
        
        // Send the full packet back to the terminal
        apdu.setOutgoingAndSend((short) 0, totalLength);
    }

    /* 
    private void processCheckInTier2Step2(APDU apdu) {
        if (currentState != STATE_ACTIVE) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();

        // 1. Initialize verifier for Terminal Authentication
        verifier.init(terminalPublicKey, Signature.MODE_VERIFY);
        
        // 2. Perform signature verification (Security Protocol SR1)
        // Ensure the terminal is authorized. Adjust offset based on packet structure.
        // if (!verifier.verify(buffer, offset_to_data, len_data, buffer, offset_to_sig, len_sig)) ...

        // 3. Subscription Expiry Check
        if (Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA, expiryDate, (short) 0, (short) 4) > 0) {
            currentState = STATE_INACTIVE; 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // 4. Atomic Counter Update (Security Protocol SR4)
        JCSystem.beginTransaction();
        try {
            short dateCmp = Util.arrayCompare(buffer, ISO7816.OFFSET_CDATA, lastDate, (short) 0, (short) 4);
            
            if (dateCmp != (short) 0) {
                // New day: update date and reset counter
                Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, lastDate, (short) 0, (short) 4);
                dailyCounter = (byte) 0x01;
            } else {
                // Same day: increment if within limits
                if ((dailyCounter & 0xFF) >= 2) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
                dailyCounter++;
            }
            JCSystem.commitTransaction(); // Commit all changes atomically
        } catch (Exception e) {
            JCSystem.abortTransaction(); // Rollback if tear occurs
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        buffer[0] = dailyCounter;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }*/
    private void processCheckInTier2Step2(APDU apdu) {
        if (currentState != STATE_ACTIVE) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();

        // Assumption: Terminal sends the signature of the card's challenge (transientNc), the signature length (64 bytes) should match RSA algorithm
        if (bytesRead < (short) 64) 
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        // 2. Mutual Authentication: Verify the terminal signature
        // The terminal must sign the 'transientNc' generated in Step 1
        verifier.init(terminalPublicKey, Signature.MODE_VERIFY);
        
        // We verify transientNc against the signature provided by the terminal (in the APDU buffer)
        if (!verifier.verify(transientNc, (short) 0, (short) 16, 
                            buffer, ISO7816.OFFSET_CDATA, (short) 64)) {
            // If verification fails the terminal is not authorized (Fake reader attack)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        // 3. Business Logic: Expiry and Counter
        // Check Subscription Expiry: If today > expiryDate, card is invalid
        if (Util.arrayCompare(buffer, (short)(ISO7816.OFFSET_CDATA + 64), expiryDate, (short) 0, (short) 4) > 0) {
            JCSystem.beginTransaction();
            currentState = STATE_INACTIVE;
            JCSystem.commitTransaction();
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // 4. Atomic Counter Update, only update the counter if the terminal is verified and valid.
        JCSystem.beginTransaction();
        try {
            short dateCmp = Util.arrayCompare(buffer, (short)(ISO7816.OFFSET_CDATA + 64), lastDate, (short) 0, (short) 4);
            
            if (dateCmp != (short) 0) {
                // New day detected: Update date and reset counter
                Util.arrayCopy(buffer, (short)(ISO7816.OFFSET_CDATA + 64), lastDate, (short) 0, (short) 4);
                dailyCounter = (byte) 0x01;
            } else {
                // Same day: check limits (max 2 accesses)
                if ((dailyCounter & 0xFF) >= 2) 
                    ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
                dailyCounter++;
            }
            JCSystem.commitTransaction();
        } catch (Exception e) {
            JCSystem.abortTransaction();
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        // Return the updated counter to the terminal
        buffer[0] = dailyCounter;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    /* 
    private void processGetCert(APDU apdu, byte[] buffer) {
        Util.arrayCopy(certC, (short) 0, buffer, (short) 0, (short) 135);
        apdu.setOutgoingAndSend((short) 0, (short) 135);
    }*/
    private void processGetCert(APDU apdu, byte[] buffer) {
        if (currentState == STATE_BLOCKED) 
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        
        //copy certificarte into the sendind buffer
        Util.arrayCopy(certC, (short) 0, buffer, (short) 0, (short) certC.length);
        //send the packet back to the terminal
        apdu.setOutgoingAndSend((short) 0, (short) certC.length);
    }
}