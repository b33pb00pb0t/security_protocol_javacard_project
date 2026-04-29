package com.sports.recreation.frontend;

public class AuthService {
    public enum Role {
        ADMIN,
        MASTER,
        INVALID
    }

    public Role login(String password) {
        if ("admin123".equals(password)) {
            return Role.ADMIN;
        }

        if ("master123".equals(password)) {
            return Role.MASTER;
        }

        return Role.INVALID;
    }
}