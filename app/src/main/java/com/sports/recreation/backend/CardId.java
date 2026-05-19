package com.sports.recreation.backend;

import java.math.BigInteger;

public final class CardId {
    private static final BigInteger MAX_CARD_ID = new BigInteger("FFFFFFFF", 16);

    private CardId() {
    }

    public static String normalize(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) {
            throw new IllegalArgumentException("Member ID cannot be empty");
        }

        String value = rawId.trim().toUpperCase();
        boolean explicitHex = false;
        if (value.startsWith("0X")) {
            value = value.substring(2);
            explicitHex = true;
        }

        if (value.isEmpty()) {
            throw new IllegalArgumentException("Member ID cannot be empty");
        }

        boolean hexShape = value.matches("[0-9A-F]+");
        boolean decimalShape = value.matches("[0-9]+");
        if (!hexShape) {
            throw new IllegalArgumentException("Member ID must be decimal or hexadecimal");
        }

        int radix = (explicitHex || value.length() == 8 || !decimalShape) ? 16 : 10;
        BigInteger parsed = new BigInteger(value, radix);
        if (parsed.signum() < 0 || parsed.compareTo(MAX_CARD_ID) > 0) {
            throw new IllegalArgumentException("Member ID must fit in 4 bytes");
        }

        return String.format("%08X", parsed.longValue());
    }

    public static byte[] toBytes(String rawId) {
        String normalized = normalize(rawId);
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) {
            int start = i * 2;
            bytes[i] = (byte) Integer.parseInt(normalized.substring(start, start + 2), 16);
        }
        return bytes;
    }
}
