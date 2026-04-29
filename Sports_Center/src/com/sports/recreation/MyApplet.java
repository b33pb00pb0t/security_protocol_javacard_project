package com.example;
import javacard.framework.*;

public class MyApplet extends Applet {
    private static final short MAX_NAME_LENGTH = (short) 15;
    private static final short DATE_LENGTH = (short) 8;  //[2][0][2][6][1][2][3][1] YYYYMMDD

//identity
    private byte[] memberID; 
    private byte[] memberName;
    private byte status;
    private byte[] expiracyDate;
                                    //BYTES SHOULD BE UNSIGNED, HERE CANNOT DEFINE UNSIGNED BYTE SO YOU LL USE THE MASK 0xFF        

//Tier 2
    private byte dailyCounter; //max 2 tier2 access per day
    private byte[] lastAccessDate;

//crypto
    //privateKey
    //certificate
    //masterPublicKey

//operations
    private static final byte INS_SET_DATA = (byte) 0x10; 
    private static final byte INS_ACTIVATE = (byte) 0x20; 
    private static final byte INS_VERIFY_TIER1 = (byte) 0x30; 
    private static final byte INS_VERIFY_TIER2 = (byte) 0x40; 

    protected MyApplet(byte[] bArray, short bOffset, byte bLength) {
        //bArray cointains applet AID 
        //bOffset buffer pointer where data begins
        //bLength length data buffer
            //in our context are optional (we dont care about veryfing the AID)

        //allocate EEPROM for customer's info, fill them later (trough the terminal)
        memberID = new byte[4];
        memberName = new byte[MAX_NAME_LENGTH];
        expiracyDate = new byte[DATE_LENGTH];
        status = 0x00; 
        dailyCounter = 0x00;
        lastAccessDate = new byte[DATE_LENGTH];
        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {//called by the terminal
        new MyApplet(bArray, bOffset, bLength); //call the constructor above
    }

    public void process(APDU apdu) {                                                                            //get the terminal commands and behave depending on it
        if (selectingApplet()) return;
        byte[] buffer = apdu.getBuffer();                                                                       //literally get the terminal commands
        
        //depending on the Terminal's command, a different op will be called and executed by the card
        switch (buffer[ISO7816.OFFSET_INS] & 0xFF) { //0xFF byte mask 

            case INS_SET_DATA:  //master terminal - member initialization
                //handleSetData(apdu);
                break;

            case INS_ACTIVATE:
                //handleSetData(apdu);
                break;

            case INS_VERIFY_TIER1:
                //handleSetData(apdu);
                break;

            case INS_VERIFY_TIER2:
                //handleSetData(apdu);
                break;
            
            //ecc...

            default: 
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }    
    }

    //SUPPORTING METHODS
    private void handleSetData(APDU apdu) {
    }




}