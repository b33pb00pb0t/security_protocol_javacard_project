package com.sports.recreation;

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

    // Proprietary CLA for the Sports Recreation Center membership protocol.
    private static final byte CLA_PROPRIETARY = (byte) 0xB0;

    // APDU instruction bytes for the membership lifecycle and check-in flow.
    // 0x10-0x1F are reserved for lifecycle management
    // 0x20-0x2F are for check-in operations.
    private static final byte INS_INITIALIZE_KEY = (byte) 0x10; // Master Terminal (MT) provisions the card's private key.
    private static final byte INS_LOAD_CERT = (byte) 0x11; // Master Terminal (MT) loads the Master-signed certificate.
    private static final byte INS_LOAD_MASTER_KEY = (byte) 0x12; // Master Terminal (MT) loads the Master public key.
    private static final byte INS_ACTIVATE = (byte) 0x13; // Administrator Terminal (AT) activates the member's account.
    private static final byte INS_BLOCK = (byte) 0x14; // Administrator Terminal (AT) blocks the member's account.
    private static final byte INS_CHECKIN_T1 = (byte) 0x20; // Open-Access Terminal (OAT) checks in for Tier 1 facilities.
    private static final byte INS_T2_STEP1 = (byte) 0x21; // Controlled-Access Terminal (CAT) begins Tier 2 mutual authentication.
    private static final byte INS_T2_STEP2 = (byte) 0x22; // Controlled-Access Terminal (CAT) completes Tier 2 mutual authentication.
    private static final byte INS_GET_CERT = (byte) 0x60; // Any Terminal queries the card's certificate for offline PKI verification.

    // Persistent lifecycle states stored in EEPROM to survive card resets.
    private static final byte STATE_INITIALIZE = (byte) 0x00;
    private static final byte STATE_ACTIVE = (byte) 0x01;
    private static final byte STATE_INACTIVE = (byte) 0x02;
    private static final byte STATE_BLOCKED = (byte) 0x03;

    // Persistent member state. These are allocated once in EEPROM and reused.
    private byte currentState;
    private byte dailyCounter;
    private final byte[] lastDate;
    private final byte[] memberId;
    
    // Large persistent objects for security provisioning (allocated in constructor).
    // certC contains the Master-signed certificate: ID_C (4 bytes) + PK_C modulus (64 bytes) + PK_C exponent (3 bytes) + MT signature (64 bytes).
    private final byte[] certC;
    private RSAPrivateKey cardPrivateKey;
    private RSAPublicKey masterPublicKey;
    private RSAPublicKey terminalPublicKey;
    private Signature rsaSignature;
    private Signature certVerifySignature;
    private Signature rsaVerify;
    private RandomData rng;
    private byte[] transientNc;
    private byte[] transientCertSig;

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        // The JavaCard runtime calls install once; the constructor performs all EEPROM allocations.
        new MembershipApplet().register();
    }

    private MembershipApplet() {
        // JavaCard has no garbage collector; all allocations must be done once at install time.
        // The arrays below are persistent EEPROM objects and must never be replaced later.
        currentState = STATE_INITIALIZE;
        dailyCounter = (byte) 0x00;
        lastDate = new byte[4];
        memberId = new byte[4];

        // Allocate space for the Master-signed certificate in EEPROM.
        // Structure: 4 bytes (ID_C) + 64 bytes (PK_C modulus) + 3 bytes (PK_C exponent) + 64 bytes (MT signature) = 135 bytes.
        certC = new byte[135];

        // Allocate the RSA key objects in EEPROM (uninitialized). Use 512-bit keys for legacy cards
        // so the full private key material can be delivered in a single standard APDU payload.
        // These Key objects are stored in persistent memory by the JavaCard runtime when built here.
        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);

        // Allocate signature and random generators in persistent memory as well.
        rsaSignature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        certVerifySignature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        terminalPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);
        rsaVerify = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

        // Keep the challenge in transient RAM so it is cleared when the card is deselected.
        transientNc = JCSystem.makeTransientByteArray((short) 16, JCSystem.CLEAR_ON_DESELECT);
        transientCertSig = JCSystem.makeTransientByteArray((short) 64, JCSystem.CLEAR_ON_DESELECT);
    }   

    @Override
    public void process(APDU apdu) throws ISOException {
        // The runtime consumes the SELECT APDU; return early to avoid state changes on select.
        if (selectingApplet()) {
            return;
        }

        // Access the APDU header to route the command without allocating new objects.
        byte[] buffer = apdu.getBuffer();

        // Enforce proprietary CLA so only our protocol commands are accepted.
        if (buffer[ISO7816.OFFSET_CLA] != CLA_PROPRIETARY) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        // Route INS values to dedicated handlers. Each handler is responsible for state checks.
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
                processBlock(apdu, buffer);
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
                processGetCert(apdu, buffer);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }
// process functions for each APDU instruction.

    private void processInitializeKey(APDU apdu, byte[] buffer) {
        // Key provisioning receives the card's private key material from the Master Terminal (MT).
        // Legacy JavaCard 2.2.1 / Java 1.5 constraints:
        // - Standard APDU data field is limited to 255 bytes; using 512-bit RSA (64+64=128 bytes)
        //   allows the MT to send modulus+exponent in a single standard APDU without extended length.
        // - This phase is split from certificate loading to avoid buffer overflows.

        // Security check: only allow initialization when the card is in STATE_INITIALIZE.
        if (currentState != STATE_INITIALIZE) {
            // If the card is already ACTIVE/INACTIVE/BLOCKED, initialization is not permitted.
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Receive incoming data. For simplicity (per spec) we expect the whole payload in one call.
        short bytesRead = apdu.setIncomingAndReceive();

        // We expect exactly 128 bytes: 64 for modulus + 64 for exponent (512-bit private key components).
        if (bytesRead != (short) 128) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Set the private key components from the received buffer. Offsets use ISO7816.OFFSET_CDATA.
        cardPrivateKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
        cardPrivateKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 64);

        // Do not change the state here; successful return will send SW=0x9000 to the MT.
    }

    private void processLoadCert(APDU apdu, byte[] buffer) {
        // Certificate loading receives the Master-signed certificate from the Master Terminal (MT).
        // The offline PKI uses this certificate to bind the card's identity and public key.
        // Structure: ID_C (4 bytes) + PK_C (67 bytes: 64-byte modulus + 3-byte exponent) + MT signature (64 bytes) = 135 bytes total.

        // Security check: only allow certificate loading when the card is in STATE_INITIALIZE.
        if (currentState != STATE_INITIALIZE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Receive incoming data. For simplicity (per spec) we expect the whole payload in one call.
        short bytesRead = apdu.setIncomingAndReceive();

        // We expect exactly 135 bytes: the complete Master-signed certificate.
        if (bytesRead != (short) 135) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Store the entire certificate into persistent memory for later retrieval.
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, certC, (short) 0, (short) 135);

        // Do not change the state here; successful return will send SW=0x9000 to the MT.
    }

    private void processLoadMasterKey(APDU apdu, byte[] buffer) {
        // Master public key provisioning is only allowed during initialization.
        if (currentState != STATE_INITIALIZE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        short bytesRead = apdu.setIncomingAndReceive();

        // Expect exactly 67 bytes: 64-byte modulus + 3-byte exponent.
        if (bytesRead != (short) 67) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        masterPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 3);
        masterPublicKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
    }

    private void processActivate(APDU apdu, byte[] buffer) {
        // 1. Enforce State: The AT can only activate a card that has been initialized by the MT 
        // or one that is currently inactive (e.g., a paused subscription).
        if (currentState != STATE_INITIALIZE && currentState != STATE_INACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Read the incoming data length
        short bytesRead = apdu.setIncomingAndReceive();
        
        // We expect exactly 8 bytes of data from the AT: 
        // 4 bytes for the Member ID + 4 bytes for the Current Date
        if (bytesRead != (short) 8) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // 2. The AT encodes the Member ID (Offset 5 is where APDU data payloads begin)
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, memberId, (short) 0, (short) 4);

        // 3. The AT initializes DailyCounter to zero and sets LastDate to current date
        Util.arrayCopy(buffer, (short)(ISO7816.OFFSET_CDATA + 4), lastDate, (short) 0, (short) 4);
        dailyCounter = (byte) 0x00;

        // 4. The card state is set to ACTIVE
        currentState = STATE_ACTIVE;
    }

    private void processBlock(APDU apdu, byte[] buffer) {
        // Card blocking is an irreversible lifecycle state for lost or stolen cards.
        // The AT can block a card from ACTIVE or INACTIVE state.
        // However, if the card is already BLOCKED, the command is redundant and an error is returned.

        // Security check: reject if the card is already in the blocked state.
        if (currentState == STATE_BLOCKED) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Transition the card to the BLOCKED state. This is irreversible without card replacement.
        currentState = STATE_BLOCKED;

        // Implicit return sends SW=0x9000 to acknowledge successful blocking.
    }

    private void processCheckInTier1(APDU apdu, byte[] buffer) { // Open-Access Terminal (OAT) check-in for Tier 1 facilities.
        // Tier 1 check-in uses a challenge-response protocol where the card signs a terminal-supplied nonce.
        // This proves the card possesses the private key without transmitting secrets.

        // State Check: Only active cards can check in.
        if (currentState != STATE_ACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Receive the nonce from the terminal.
        short bytesRead = apdu.setIncomingAndReceive();

        // We expect exactly 16 bytes of nonce from the terminal.
        if (bytesRead != (short) 16) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Initialize the signature engine with the card's private key for signing mode.
        rsaSignature.init(cardPrivateKey, Signature.MODE_SIGN);

        // Compute the signature of the 16-byte nonce.
        // We use a RAM-efficient technique: the nonce is at ISO7816.OFFSET_CDATA in the buffer,
        // and we write the resulting signature starting at offset 0, reusing the buffer space.
        short sigLen = rsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) 0);

        // Send the signature back to the terminal.
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    private void processCheckInTier2Step1(APDU apdu) {
        // Step 1 of the Tier 2 mutual authentication protocol:
        // the card receives N_T, generates N_C, signs N_T, and returns N_C || sigma_1 || Cert_C.

        if (currentState != STATE_ACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();
        short bytesRead = apdu.setIncomingAndReceive();

        if (bytesRead != (short) 16) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Generate the card challenge in transient RAM so it disappears on deselect.
        rng.generateData(transientNc, (short) 0, (short) 16);

        // Sign N_T into the space immediately after the terminal nonce.
        // This keeps the incoming challenge intact while the RSA engine writes the signature
        // into the unused tail of the APDU buffer. We then compact the response into
        // N_C || sigma_1 || Cert_C without allocating any extra memory.
        rsaSignature.init(cardPrivateKey, Signature.MODE_SIGN);
        short sigLen = rsaSignature.sign(buffer, ISO7816.OFFSET_CDATA, (short) 16, buffer, (short) (ISO7816.OFFSET_CDATA + 16));

        // Build the response in place: N_C first, then sigma_1, then the certificate.
        Util.arrayCopy(transientNc, (short) 0, buffer, (short) 0, (short) 16);
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 16), buffer, (short) 16, sigLen);
        Util.arrayCopy(certC, (short) 0, buffer, (short) 80, (short) 135);

        // The protocol response is 16 + 64 + 135 = 215 bytes.
        apdu.setOutgoingAndSend((short) 0, (short) 215);
    }

    private void processCheckInTier2Step2(APDU apdu) {
        // Step 2 completes the Tier 2 mutual-authentication flow.
        // Buffer layout:
        //   0..63   = sigma2
        //   64..134 = Cert_T (ID_T + PK_T modulus + PK_T exponent)
        //   135..198 = sigma1 from step 1
        //   199..202 = CurrentDate

        if (currentState != STATE_ACTIVE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        byte[] buffer = apdu.getBuffer();
        short totalLength = apdu.getIncomingLength();
        short bytesRead = apdu.setIncomingAndReceive();
        while (bytesRead < totalLength) {
            bytesRead += apdu.receiveBytes((short) (ISO7816.OFFSET_CDATA + bytesRead));
        }

        if (bytesRead != (short) 203) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Copy the terminal certificate signature into transient RAM now that the full APDU
        // payload has been received, then verify the certificate against the separate buffer.
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 135), transientCertSig, (short) 0, (short) 64);

        certVerifySignature.init(masterPublicKey, Signature.MODE_VERIFY);
        boolean isCertValid = certVerifySignature.verify(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 71, transientCertSig, (short) 0, (short) 64);
        if (!isCertValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }

        // Extract the terminal public key from Cert_T for future use.
        terminalPublicKey.setModulus(buffer, (short) (ISO7816.OFFSET_CDATA + 68), (short) 64);
        terminalPublicKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 132), (short) 3);

        // Verify sigma2 over N_C || CurrentDate using the terminal public key.
        rsaVerify.init(terminalPublicKey, Signature.MODE_VERIFY);
        rsaVerify.update(transientNc, (short) 0, (short) 16);
        boolean isSigValid = rsaVerify.verify(buffer, (short) (ISO7816.OFFSET_CDATA + 199), (short) 4, buffer, ISO7816.OFFSET_CDATA, (short) 64);
        if (!isSigValid) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }


        // Replay protection: clear the transient nonce once it has been authenticated.
        Util.arrayFillNonAtomic(transientNc, (short) 0, (short) 16, (byte) 0);

        // Compare CurrentDate with the last accepted date stored in EEPROM.
        short dateCmp = Util.arrayCompare(buffer, (short) (ISO7816.OFFSET_CDATA + 199), lastDate, (short) 0, (short) 4);
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

    private void processGetCert(APDU apdu, byte[] buffer) {
        // The GET_CERT command is available in any state and requires no authentication.
        // Terminals use the returned certificate for offline PKI verification:
        // 1. Use the Master Terminal's public key (PK_M) to verify the MT signature on the certificate.
        // 2. Extract ID_C (first 4 bytes) to verify the card against the synchronized block list.
        // 3. Extract PK_C (the embedded public key) to verify check-in signatures from Tier 1/Tier 2 terminals.

        byte[] responseBuffer = apdu.getBuffer();
        Util.arrayCopy(certC, (short) 0, responseBuffer, (short) 0, (short) 135);
        apdu.setOutgoingAndSend((short) 0, (short) 135);
    }
}
    