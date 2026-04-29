package com.sports.recreation.backend;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BlockListSnapshot {
    private final long version;
    private final Map<String, String> blockedCardsData;

    public BlockListSnapshot(long version, Set<String> blockedCards) {
        this.version = version;
        this.blockedCardsData = new HashMap<>();
        for (String id : blockedCards) {
            this.blockedCardsData.put(id.trim().toUpperCase(), "Unknown");
        }
    }

    public BlockListSnapshot(long version, Map<String, String> blockedCardsData) {
        this.version = version;
        this.blockedCardsData = Collections.unmodifiableMap(new HashMap<>(blockedCardsData));
    }

    public long getVersion() {
        return version;
    }

    public boolean contains(String idc) {
        return blockedCardsData.containsKey(idc.trim().toUpperCase());
    }

    public Set<String> getBlockedCards() {
        return blockedCardsData.keySet();
    }
    
    public Map<String, String> getBlockedCardsData() {
        return blockedCardsData;
    }
}