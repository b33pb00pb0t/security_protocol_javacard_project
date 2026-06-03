package backend;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TerminalOfflineCache {
    private TerminalOfflineSnapshot snapshot = TerminalOfflineSnapshot.empty();

    public synchronized TerminalOfflineSnapshot sync(Collection<MemberRecord> members, BlockListSnapshot blockListSnapshot) {
        Map<String, MemberRecord> activeMembers = new HashMap<>();
        for (MemberRecord member : members) {
            if (MemberRecord.STATUS_ACTIVE.equals(member.getStatus())) {
                activeMembers.put(member.getMemberId(), member);
            }
        }

        Map<String, String> blockedCards = new HashMap<>();
        for (Map.Entry<String, String> entry : blockListSnapshot.getBlockedCardsData().entrySet()) {
            blockedCards.put(CardId.normalize(entry.getKey()), entry.getValue());
        }

        snapshot = TerminalOfflineSnapshot.synced(LocalDateTime.now(),
                blockListSnapshot.getVersion(), activeMembers, blockedCards);
        return snapshot;
    }

    public synchronized TerminalOfflineSnapshot getSnapshot() {
        return snapshot;
    }
}
