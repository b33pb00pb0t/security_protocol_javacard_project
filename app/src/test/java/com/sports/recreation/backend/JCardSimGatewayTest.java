package com.sports.recreation.backend;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JCardSimGatewayTest {
    @Test
    public void tier2CounterResetsWhenDateBytesChange() {
        JCardSimGateway gateway = new JCardSimGateway();
        LocalDate firstDay = LocalDate.of(2026, 5, 12);
        LocalDate nextDay = LocalDate.of(2026, 5, 13);

        gateway.provision("1234");
        gateway.activate("1234", firstDay);

        assertContains(gateway.checkInTier2("1234", firstDay).getMessage(), "DailyCounter=1");
        assertContains(gateway.checkInTier2("1234", firstDay).getMessage(), "DailyCounter=2");
        assertFalse(gateway.checkInTier2("1234", firstDay).isSuccess());

        JCardSimGateway.CardAccessResult nextDayResult = gateway.checkInTier2("1234", nextDay);
        assertTrue(nextDayResult.isSuccess());
        assertContains(nextDayResult.getMessage(), "DailyCounter=1");
    }

    @Test
    public void appletActiveFlagTracksActivationAndBlocking() {
        JCardSimGateway gateway = new JCardSimGateway();

        gateway.provision("1234");
        assertFalse(gateway.isAppletActive("1234"));
        gateway.activate("1234", LocalDate.of(2026, 5, 12));
        assertTrue(gateway.isAppletActive("1234"));
        gateway.blockIfPresent("1234");
        assertFalse(gateway.isAppletActive("1234"));
    }

    private void assertContains(String actual, String expected) {
        assertTrue("Expected [" + actual + "] to contain [" + expected + "]", actual.contains(expected));
    }
}
