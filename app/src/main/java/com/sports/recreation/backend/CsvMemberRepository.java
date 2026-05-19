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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CsvMemberRepository implements MemberRepository {
    private static final String HEADER = "MemberId,Status,ExpiryDate,PackageType,Phone,CreatedAt,UpdatedAt";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final File storageFile;
    private final Map<String, MemberRecord> members = new LinkedHashMap<>();

    public CsvMemberRepository(String filePath) {
        this.storageFile = new File(filePath);
        loadFromFile();
    }

    @Override
    public synchronized MemberRecord find(String memberId) {
        return members.get(CardId.normalize(memberId));
    }

    @Override
    public synchronized void save(MemberRecord record) {
        members.put(record.getMemberId(), record);
        saveAll();
    }

    @Override
    public synchronized Collection<MemberRecord> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    public synchronized MemberRecord ensureInitialized(String memberId, String packageType) {
        String normalized = CardId.normalize(memberId);
        String now = now();
        MemberRecord existing = members.get(normalized);
        MemberRecord updated;
        if (existing == null) {
            updated = new MemberRecord(normalized, MemberRecord.STATUS_INITIALIZED, "", packageType, "", now, now);
        } else if (MemberRecord.STATUS_BLOCKED.equals(existing.getStatus())) {
            updated = existing;
        } else {
            updated = existing.withPackageType(packageType, now);
        }
        save(updated);
        return updated;
    }

    public synchronized MemberRecord activate(String memberId, String expiryDate, String phone) {
        String normalized = CardId.normalize(memberId);
        String now = now();
        MemberRecord existing = members.get(normalized);
        if (existing == null) {
            existing = new MemberRecord(normalized, MemberRecord.STATUS_INITIALIZED, "", "STANDARD", "", now, now);
        }
        MemberRecord updated = existing.withActivation(expiryDate, phone, now);
        save(updated);
        return updated;
    }

    public synchronized MemberRecord deactivate(String memberId) {
        return updateStatus(memberId, MemberRecord.STATUS_INACTIVE);
    }

    public synchronized MemberRecord block(String memberId) {
        return updateStatus(memberId, MemberRecord.STATUS_BLOCKED);
    }

    public synchronized MemberRecord renew(String memberId, String expiryDate) {
        String normalized = CardId.normalize(memberId);
        MemberRecord existing = requireExisting(normalized);
        MemberRecord updated = existing.withRenewal(expiryDate, now());
        save(updated);
        return updated;
    }

    private MemberRecord updateStatus(String memberId, String status) {
        String normalized = CardId.normalize(memberId);
        MemberRecord existing = requireExisting(normalized);
        MemberRecord updated = existing.withStatus(status, now());
        save(updated);
        return updated;
    }

    private MemberRecord requireExisting(String normalizedMemberId) {
        MemberRecord existing = members.get(normalizedMemberId);
        if (existing == null) {
            throw new IllegalStateException("Member " + normalizedMemberId + " is not registered");
        }
        return existing;
    }

    private void loadFromFile() {
        if (!storageFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line = reader.readLine();
            if (line != null && !line.toLowerCase().startsWith("memberid")) {
                processLine(line);
            }
            while ((line = reader.readLine()) != null) {
                processLine(line);
            }
        } catch (IOException e) {
            System.err.println("Failed to load member CSV: " + e.getMessage());
        }
    }

    private void processLine(String line) {
        if (line.trim().isEmpty()) {
            return;
        }
        String[] parts = line.split(",", -1);
        if (parts.length < 1 || parts[0].trim().isEmpty()) {
            return;
        }

        String memberId = parts[0];
        String status = part(parts, 1);
        String expiryDate = part(parts, 2);
        String packageType = part(parts, 3);
        String phone = part(parts, 4);
        String createdAt = part(parts, 5);
        String updatedAt = part(parts, 6);
        MemberRecord record = new MemberRecord(memberId, status, expiryDate, packageType, phone, createdAt, updatedAt);
        members.put(record.getMemberId(), record);
    }

    private void saveAll() {
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile, false))) {
            writer.write(HEADER);
            writer.newLine();
            for (MemberRecord record : members.values()) {
                writer.write(join(record.getMemberId(), record.getStatus(), record.getExpiryDate(),
                        record.getPackageType(), record.getPhone(), record.getCreatedAt(), record.getUpdatedAt()));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save member CSV", e);
        }
    }

    private String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(sanitize(values[i]));
        }
        return builder.toString();
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(',', ' ').trim();
    }

    private String part(String[] parts, int index) {
        if (index >= parts.length) {
            return "";
        }
        return parts[index].trim();
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }
}
