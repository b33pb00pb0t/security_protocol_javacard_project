package com.sports.recreation.frontend;

public class AuthService {
    public enum Role {
        ADMIN,
        MASTER,
        ACCESS,
        INVALID
    }

    public Role login(String password) {
        if ("admin123".equals(password)) {
            return Role.ADMIN;
        }

        if ("master123".equals(password)) {
            return Role.MASTER;
        }

        if ("access123".equals(password)) {
            return Role.ACCESS;
        }

        return Role.INVALID;
    }
}
