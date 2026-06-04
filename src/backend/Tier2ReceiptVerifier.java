package backend;

import applet.ProtocolConstants;

import java.security.PublicKey;
import java.security.Signature;

/**
 * Host-side verifier for the signed Tier 2 receipt returned by the card.
 */
public final class Tier2ReceiptVerifier {
    private Tier2ReceiptVerifier() {
    }

    public static Result verify(byte[] receipt, PublicKey cardPublicKey, byte[] cardId,
                                byte[] terminalId, byte[] date, byte[] terminalNonce,
                                byte[] cardNonce) throws Exception {
        requireLength(receipt, ProtocolConstants.TIER2_RECEIPT_LENGTH, "Tier 2 receipt");
        requireLength(cardId, 4, "Card ID");
        requireLength(terminalId, 4, "Terminal ID");
        requireLength(date, 4, "Date");
        requireLength(terminalNonce, ProtocolConstants.NONCE_LENGTH, "Terminal nonce");
        requireLength(cardNonce, ProtocolConstants.NONCE_LENGTH, "Card nonce");

        int resultCode = receipt[0] & 0xFF;
        int dailyCounter = receipt[1] & 0xFF;
        int transactionCounter = ((receipt[2] & 0xFF) << 8) | (receipt[3] & 0xFF);

        byte[] signedData = signedData(receipt[0], receipt[1], receipt[2], receipt[3],
                cardId, terminalId, date, terminalNonce, cardNonce);
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(cardPublicKey);
        verifier.update(signedData);
        if (!verifier.verify(receipt, 4, ProtocolConstants.SIGNATURE_LENGTH)) {
            throw new SecurityException("Tier 2 receipt signature verification failed");
        }
        if (receipt[0] != ProtocolConstants.RESULT_GRANTED) {
            throw new SecurityException("Tier 2 receipt returned non-granted result code " + resultCode);
        }
        return new Result(resultCode, dailyCounter, transactionCounter);
    }

    public static byte[] signedData(byte resultCode, byte dailyCounter, byte transactionCounterHigh,
                                    byte transactionCounterLow, byte[] cardId, byte[] terminalId,
                                    byte[] date, byte[] terminalNonce, byte[] cardNonce) {
        byte[] data = new byte[ProtocolConstants.TIER2_RECEIPT_SIGNED_DATA_LENGTH];
        int offset = 0;
        data[offset++] = ProtocolConstants.OP_T2_RESULT;
        System.arraycopy(cardId, 0, data, offset, 4);
        offset += 4;
        System.arraycopy(terminalId, 0, data, offset, 4);
        offset += 4;
        System.arraycopy(date, 0, data, offset, 4);
        offset += 4;
        System.arraycopy(terminalNonce, 0, data, offset, ProtocolConstants.NONCE_LENGTH);
        offset += ProtocolConstants.NONCE_LENGTH;
        System.arraycopy(cardNonce, 0, data, offset, ProtocolConstants.NONCE_LENGTH);
        offset += ProtocolConstants.NONCE_LENGTH;
        data[offset++] = dailyCounter;
        data[offset++] = transactionCounterHigh;
        data[offset++] = transactionCounterLow;
        data[offset] = resultCode;
        return data;
    }

    private static void requireLength(byte[] value, int expected, String label) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(label + " must be " + expected + " bytes");
        }
    }

    public static final class Result {
        private final int resultCode;
        private final int dailyCounter;
        private final int transactionCounter;

        private Result(int resultCode, int dailyCounter, int transactionCounter) {
            this.resultCode = resultCode;
            this.dailyCounter = dailyCounter;
            this.transactionCounter = transactionCounter;
        }

        public int getResultCode() {
            return resultCode;
        }

        public int getDailyCounter() {
            return dailyCounter;
        }

        public int getTransactionCounter() {
            return transactionCounter;
        }
    }
}
