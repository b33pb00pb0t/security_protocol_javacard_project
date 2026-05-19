package com.sports.recreation.backend;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CsvAuditLogger implements AuditLogger {
    private static final String HEADER = "Timestamp,Terminal,MemberId,Action,Result,Message";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final File storageFile;

    public CsvAuditLogger(String filePath) {
        this.storageFile = new File(filePath);
    }

    @Override
    public synchronized void log(String terminal, String memberId, String action, boolean success, String message) {
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        boolean newFile = !storageFile.exists() || storageFile.length() == 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile, true))) {
            if (newFile) {
                writer.write(HEADER);
                writer.newLine();
            }
            writer.write(join(LocalDateTime.now().format(TIMESTAMP_FORMAT), terminal,
                    normalizeMember(memberId), action, success ? "SUCCESS" : "DENIED", message));
            writer.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write audit log", e);
        }
    }

    @Override
    public synchronized List<AuditEvent> readAll() {
        if (!storageFile.exists()) {
            return Collections.emptyList();
        }

        List<AuditEvent> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line = reader.readLine();
            if (line != null && !line.toLowerCase().startsWith("timestamp")) {
                processLine(line, events);
            }
            while ((line = reader.readLine()) != null) {
                processLine(line, events);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read audit log", e);
        }
        return Collections.unmodifiableList(events);
    }

    private void processLine(String line, List<AuditEvent> events) {
        if (line.trim().isEmpty()) {
            return;
        }
        String[] parts = line.split(",", 6);
        if (parts.length < 6) {
            return;
        }
        events.add(new AuditEvent(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
    }

    private String normalizeMember(String memberId) {
        try {
            return CardId.normalize(memberId);
        } catch (RuntimeException e) {
            return safe(memberId);
        }
    }

    private String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(safe(values[i]));
        }
        return builder.toString();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
