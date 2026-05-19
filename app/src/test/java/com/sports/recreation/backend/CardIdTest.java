package com.sports.recreation.backend;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CardIdTest {
    @Test
    public void normalizesDecimalAndHexIdsToFourByteHex() {
        assertEquals("000004D2", CardId.normalize("1234"));
        assertEquals("000004D2", CardId.normalize("000004D2"));
        assertEquals("000004D2", CardId.normalize("0x000004d2"));
        assertArrayEquals(new byte[] {0x00, 0x00, 0x04, (byte) 0xD2}, CardId.toBytes("1234"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdsOutsideFourBytes() {
        CardId.normalize("0x100000000");
    }
}
