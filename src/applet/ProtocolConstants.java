package applet;

/**
 * JavaCard-compatible protocol constants shared by the applet and host code.
 */
public final class ProtocolConstants {
    public static final byte ROLE_CARD = (byte) 0x01;
    public static final byte ROLE_MASTER_TERMINAL = (byte) 0x02;
    public static final byte ROLE_ADMIN_TERMINAL = (byte) 0x03;
    public static final byte ROLE_OPEN_ACCESS_TERMINAL = (byte) 0x04;
    public static final byte ROLE_CONTROLLED_ACCESS_TERMINAL = (byte) 0x05;

    public static final byte OP_ACTIVATE = (byte) 0x01;
    public static final byte OP_BLOCK = (byte) 0x02;
    public static final byte OP_RENEW = (byte) 0x03;
    public static final byte OP_T2_RESULT = (byte) 0x04;
    public static final byte RESULT_GRANTED = (byte) 0x00;

    public static final short CERT_ROLE_OFFSET = (short) 0;
    public static final short CERT_ID_OFFSET = (short) 1;
    public static final short CERT_MODULUS_OFFSET = (short) 5;
    public static final short CERT_EXPONENT_OFFSET = (short) 69;
    public static final short CERT_SIGNATURE_OFFSET = (short) 72;

    public static final short CERTIFICATE_BODY_LENGTH = (short) 72;
    public static final short SIGNATURE_LENGTH = (short) 64;
    public static final short CARD_CERTIFICATE_LENGTH = (short) 136;
    public static final short TIER2_STEP1_RESPONSE_LENGTH = (short) 216;
    public static final short TIER2_STEP2_PAYLOAD_LENGTH = (short) 204;
    public static final short TIER2_RECEIPT_LENGTH = (short) 68;
    public static final short TIER2_RECEIPT_SIGNED_DATA_LENGTH = (short) 49;
    public static final short NONCE_LENGTH = (short) 16;

    public static final short ADMIN_ACTIVATE_DATA_LENGTH = (short) 13;
    public static final short ADMIN_BLOCK_DATA_LENGTH = (short) 5;
    public static final short ADMIN_ACTIVATE_PAYLOAD_LENGTH = (short) 213;
    public static final short ADMIN_BLOCK_PAYLOAD_LENGTH = (short) 205;

    private ProtocolConstants() {
    }
}
