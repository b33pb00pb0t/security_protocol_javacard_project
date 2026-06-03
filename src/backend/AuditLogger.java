package backend;

import java.util.List;

public interface AuditLogger {
    void log(String terminal, String memberId, String action, boolean success, String message);

    List<AuditEvent> readAll();
}
