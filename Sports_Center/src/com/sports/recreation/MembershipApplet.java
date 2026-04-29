package com.sports.recreation;

import javacard.framework.APDU;
import javacard.framework.Applet;
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
    private static final byte INS_INITIALIZE = (byte) 0x10; // Master Terminal (MT) initializes the card.
    private static final byte INS_ACTIVATE = (byte) 0x12; // Administrator Terminal (AT) activates the member's account.
    private static final byte INS_BLOCK = (byte) 0x14; // Administrator Terminal (AT) blocks the member's account.
    private static final byte INS_CHECKIN_T1 = (byte) 0x20; // Open-Access Terminal (OAT) checks in for Tier 1 facilities.
    private static final byte INS_CHECKIN_T2 = (byte) 0x22; // Controlled-Access Terminal (CAT) checks in for Tier 2 facilities.

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
    private final byte[] cardCertificate;
    private RSAPrivateKey cardPrivateKey;
    private RSAPublicKey masterPublicKey;
    private Signature rsaSignature;
    private RandomData randomData;

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

        // Allocate space for a standard 512-byte certificate in EEPROM. Be mindful of card EEPROM limits.
        cardCertificate = new byte[512];

        // Allocate the RSA key objects in EEPROM (uninitialized). Use 512-bit keys for legacy cards
        // so the full private key material can be delivered in a single standard APDU payload.
        // These Key objects are stored in persistent memory by the JavaCard runtime when built here.
        cardPrivateKey = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_512, false);
        masterPublicKey = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_512, false);

        // Allocate signature and random generators in persistent memory as well.
        rsaSignature = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
        randomData = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
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
            case INS_INITIALIZE:
                processInitialize(apdu, buffer);
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
            case INS_CHECKIN_T2:
                processCheckInTier2(apdu, buffer);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }
// process functions for each APDU instruction.

    private void processInitialize(APDU apdu, byte[] buffer) {
        // Initialization receives key material from the Master Terminal (MT).
        // Legacy JavaCard 2.2.1 / Java 1.5 constraints:
        // - Standard APDU data field is limited to 255 bytes; using 512-bit RSA (64+64=128 bytes)
        //   allows the MT to send modulus+exponent in a single standard APDU without extended length.
        // - All persistent objects (keys, arrays) must be allocated at install time; no 'new' in handlers.

        // Security check: only allow initialization when the card is in STATE_INITIALIZE.
        if (currentState != STATE_INITIALIZE) {
            // If the card is already ACTIVE/INACTIVE/BLOCKED, initialization is not permitted.
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        // Receive incoming data. For simplicity (per spec) we expect the whole payload in one call.
        short bytesRead = apdu.setIncomingAndReceive();

        // We expect exactly 128 bytes: 64 for modulus + 64 for exponent (512-bit key components).
        if (bytesRead != (short) 128) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // Set the private key components from the received buffer. Offsets use ISO7816.OFFSET_CDATA.
        cardPrivateKey.setModulus(buffer, ISO7816.OFFSET_CDATA, (short) 64);
        cardPrivateKey.setExponent(buffer, (short) (ISO7816.OFFSET_CDATA + 64), (short) 64);

        // Do not change the state here; successful return will send SW=0x9000 to the MT.
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
        // TODO: Permit blocking from any state except STATE_BLOCKED.
        // TODO: Transition to STATE_BLOCKED and preserve existing identifiers.
        ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
    }

    private void processCheckInTier1(APDU apdu, byte[] buffer) {
        // TODO: Require STATE_ACTIVE and validate dailyCounter limits for tier 1 access.
        // TODO: Update lastDate and dailyCounter using existing EEPROM arrays.
        ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
    }

    private void processCheckInTier2(APDU apdu, byte[] buffer) {
        // TODO: Require STATE_ACTIVE and validate tier 2 entitlements before incrementing.
        // TODO: Update lastDate and dailyCounter using existing EEPROM arrays.
        ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
    }
}
