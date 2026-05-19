package com.sports.recreation.backend;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ApduDateCodecTest {
    @Test
    public void encodesAndDecodesBcdDateBytes() {
        LocalDate date = LocalDate.of(2026, 5, 19);

        byte[] encoded = ApduDateCodec.encode(date);

        assertArrayEquals(new byte[] {0x20, 0x26, 0x05, 0x19}, encoded);
        assertEquals(date, ApduDateCodec.decode(encoded));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongLengthDateBytes() {
        ApduDateCodec.decode(new byte[] {0x20, 0x26, 0x05});
    }
}
