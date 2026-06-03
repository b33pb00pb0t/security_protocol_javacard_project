package backend;

import java.util.Collections;
import java.util.List;

public class NoOpAuditLogger implements AuditLogger {
    @Override
    public void log(String terminal, String memberId, String action, boolean success, String message) {
        // Intentionally empty for tests or mock wiring that does not need persistence.
    }

    @Override
    public List<AuditEvent> readAll() {
        return Collections.emptyList();
    }
}
