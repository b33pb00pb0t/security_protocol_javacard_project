package com.sports.recreation.backend;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CsvBlockListRepository implements BlockListRepository {
    private final Map<String, String> blockListEntries = new HashMap<>(); // memberId -> timestamp
    private long version = 0;
    private final File storageFile;

    public CsvBlockListRepository(String filePath) {
        this.storageFile = new File(filePath);
        loadFromFile();
    }

    private void loadFromFile() {
        if (!storageFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line = reader.readLine(); 
            // Skip header if present
            if (line != null && !line.toLowerCase().startsWith("memberid")) {
                processLine(line);
            }

            while ((line = reader.readLine()) != null) {
                processLine(line);
            }
            version = blockListEntries.size();
        } catch (IOException e) {
            System.err.println("Failed to load CSV block list: " + e.getMessage());
        }
    }

    private void processLine(String line) {
        if (line.trim().isEmpty()) return;
        String[] parts = line.split(",");
        if (parts.length > 0 && !parts[0].trim().isEmpty()) {
            String idc = normalize(parts[0]);
            String timestamp = parts.length > 1 ? parts[1].trim() : "Unknown";
            blockListEntries.put(idc, timestamp);
        }
    }

    private void saveToFile(String idc, String timestamp) {
        boolean isNewFile = !storageFile.exists() || storageFile.length() == 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile, true))) {
            if (isNewFile) {
                writer.write("MemberId,ReportedAt");
                writer.newLine();
            }
            writer.write(idc + "," + timestamp);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Failed to save to CSV block list: " + e.getMessage());
        }
    }

    @Override
    public synchronized void blockCard(String idc) {
        String normalizedIdc = normalize(idc);

        if (!blockListEntries.containsKey(normalizedIdc)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            blockListEntries.put(normalizedIdc, timestamp);
            version++;
            saveToFile(normalizedIdc, timestamp);
        }
    }

    @Override
    public synchronized boolean isBlocked(String idc) {
        return blockListEntries.containsKey(normalize(idc));
    }

    @Override
    public synchronized Set<String> getBlockedCards() {
        return Collections.unmodifiableSet(new HashSet<>(blockListEntries.keySet()));
    }

    public synchronized Map<String, String> getBlockListEntries() {
        return Collections.unmodifiableMap(new HashMap<>(blockListEntries));
    }

    @Override
    public synchronized long getVersion() {
        return version;
    }

    private String normalize(String idc) {
        if (idc == null || idc.trim().isEmpty()) {
            throw new IllegalArgumentException("IDC cannot be empty");
        }
        return idc.trim().toUpperCase();
    }
}