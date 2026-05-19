package com.sports.recreation.backend;

import java.time.LocalDate;

public final class ApduDateCodec {
    private ApduDateCodec() {
    }

    public static byte[] encode(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        int year = date.getYear();
        return new byte[] {
                toBcd(year / 100),
                toBcd(year % 100),
                toBcd(date.getMonthValue()),
                toBcd(date.getDayOfMonth())
        };
    }

    public static LocalDate decode(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            throw new IllegalArgumentException("APDU date must be exactly 4 bytes");
        }
        int year = fromBcd(bytes[0]) * 100 + fromBcd(bytes[1]);
        int month = fromBcd(bytes[2]);
        int day = fromBcd(bytes[3]);
        return LocalDate.of(year, month, day);
    }

    private static byte toBcd(int value) {
        if (value < 0 || value > 99) {
            throw new IllegalArgumentException("BCD value must be between 0 and 99");
        }
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    private static int fromBcd(byte value) {
        int high = (value >> 4) & 0x0F;
        int low = value & 0x0F;
        if (high > 9 || low > 9) {
            throw new IllegalArgumentException("Invalid BCD date byte");
        }
        return high * 10 + low;
    }
}
