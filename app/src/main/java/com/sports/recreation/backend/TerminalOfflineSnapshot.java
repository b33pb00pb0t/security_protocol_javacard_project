package com.sports.recreation.backend;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TerminalOfflineSnapshot {
    private final boolean synced;
    private final LocalDateTime syncedAt;
    private final long blockListVersion;
    private final Map<String, MemberRecord> activeMembers;
    private final Map<String, String> blockedCards;

    private TerminalOfflineSnapshot(boolean synced, LocalDateTime syncedAt, long blockListVersion,
                                    Map<String, MemberRecord> activeMembers,
                                    Map<String, String> blockedCards) {
        this.synced = synced;
        this.syncedAt = syncedAt;
        this.blockListVersion = blockListVersion;
        this.activeMembers = Collections.unmodifiableMap(new HashMap<>(activeMembers));
        this.blockedCards = Collections.unmodifiableMap(new HashMap<>(blockedCards));
    }

    public static TerminalOfflineSnapshot empty() {
        return new TerminalOfflineSnapshot(false, null, 0L,
                Collections.<String, MemberRecord>emptyMap(), Collections.<String, String>emptyMap());
    }

    public static TerminalOfflineSnapshot synced(LocalDateTime syncedAt, long blockListVersion,
                                                 Map<String, MemberRecord> activeMembers,
                                                 Map<String, String> blockedCards) {
        return new TerminalOfflineSnapshot(true, syncedAt, blockListVersion, activeMembers, blockedCards);
    }

    public boolean isSynced() {
        return synced;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public long getBlockListVersion() {
        return blockListVersion;
    }

    public int getActiveCount() {
        return activeMembers.size();
    }

    public int getBlockedCount() {
        return blockedCards.size();
    }

    public boolean isBlocked(String memberId) {
        return blockedCards.containsKey(CardId.normalize(memberId));
    }

    public MemberRecord findActiveMember(String memberId) {
        return activeMembers.get(CardId.normalize(memberId));
    }

    public String describe() {
        if (!synced) {
            return "Terminal cache has not been synced.";
        }
        return "Terminal cache synced at " + syncedAt
                + " | Active cards: " + activeMembers.size()
                + " | Blocked cards: " + blockedCards.size()
                + " | Block version: " + blockListVersion;
    }
}
