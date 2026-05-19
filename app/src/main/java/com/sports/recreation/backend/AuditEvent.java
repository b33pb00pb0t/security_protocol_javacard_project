package com.sports.recreation.backend;

public class AuditEvent {
    private final String timestamp;
    private final String terminal;
    private final String memberId;
    private final String action;
    private final String result;
    private final String message;

    public AuditEvent(String timestamp, String terminal, String memberId, String action, String result, String message) {
        this.timestamp = timestamp;
        this.terminal = terminal;
        this.memberId = memberId;
        this.action = action;
        this.result = result;
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getTerminal() {
        return terminal;
    }

    public String getMemberId() {
        return memberId;
    }

    public String getAction() {
        return action;
    }

    public String getResult() {
        return result;
    }

    public String getMessage() {
        return message;
    }
}
