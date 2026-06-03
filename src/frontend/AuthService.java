package frontend;

import backend.AuditLogger;
import backend.NoOpAuditLogger;

public class AuthService {
    private final AuditLogger auditLogger;

    public enum Role {
        ADMIN,
        MASTER,
        ACCESS,
        INVALID
    }

    public AuthService() {
        this(new NoOpAuditLogger());
    }

    public AuthService(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public Role login(String password) {
        if ("admin123".equals(password)) {
            auditLogger.log("AUTH", "", "LOGIN_ADMIN", true, "Admin login successful");
            return Role.ADMIN;
        }

        if ("master123".equals(password)) {
            auditLogger.log("AUTH", "", "LOGIN_MASTER", true, "Master login successful");
            return Role.MASTER;
        }

        if ("access123".equals(password)) {
            auditLogger.log("AUTH", "", "LOGIN_ACCESS", true, "Access login successful");
            return Role.ACCESS;
        }

        auditLogger.log("AUTH", "", "LOGIN", false, "Invalid password");
        return Role.INVALID;
    }
}
