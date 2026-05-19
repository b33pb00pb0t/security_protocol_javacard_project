package com.sports.recreation.frontend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuthServiceTest {
    @Test
    public void mapsPasswordsToTerminalRoles() {
        AuthService authService = new AuthService();

        assertEquals(AuthService.Role.MASTER, authService.login("master123"));
        assertEquals(AuthService.Role.ADMIN, authService.login("admin123"));
        assertEquals(AuthService.Role.ACCESS, authService.login("access123"));
        assertEquals(AuthService.Role.INVALID, authService.login("wrong"));
    }
}
