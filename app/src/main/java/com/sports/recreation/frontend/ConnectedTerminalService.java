package com.sports.recreation.frontend;

import com.sports.recreation.backend.BlockListRepository;
import com.sports.recreation.backend.CardId;
import com.sports.recreation.backend.CsvMemberRepository;
import com.sports.recreation.backend.JCardSimGateway;
import com.sports.recreation.backend.MemberRecord;
import com.sports.recreation.backend.TerminalSyncService;

import java.time.LocalDate;
import java.util.Map;

public class ConnectedTerminalService implements TerminalService {
    private final CsvMemberRepository memberRepository;
    private final BlockListRepository blockListRepository;
    private final TerminalSyncService syncService;
    private final JCardSimGateway cardGateway;

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    JCardSimGateway cardGateway) {
        this.memberRepository = memberRepository;
        this.blockListRepository = blockListRepository;
        this.syncService = syncService;
        this.cardGateway = cardGateway;
    }

    @Override
    public String activateCard(String memberId, String expiryDate) {
        return activateCard(memberId, expiryDate, "");
    }

    @Override
    public String activateCard(String memberId, String expiryDate, String phoneNumber) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return "ERROR: Card " + normalized + " is BLOCKED. Cannot activate.";
            }
            if (!cardGateway.hasSession(normalized)) {
                return "ERROR: Initialize simulator card " + normalized + " from the Master terminal first.";
            }

            if (!cardGateway.isAppletActive(normalized)) {
                cardGateway.activate(normalized, LocalDate.now());
            }
            MemberRecord record = memberRepository.activate(normalized, expiryDate, phoneNumber);
            return "Card " + record.getMemberId() + " activated until " + record.getExpiryDate()
                    + ". Phone linked: " + displayEmpty(record.getPhone(), "N/A");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String deactivateCard(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            MemberRecord record = memberRepository.deactivate(normalized);
            return "Card " + record.getMemberId() + " deactivated in backend policy.";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String renewMembership(String memberId, String newExpiryDate) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return "ERROR: Card " + normalized + " is BLOCKED. Cannot renew.";
            }
            MemberRecord record = memberRepository.renew(normalized, newExpiryDate);
            return "Membership for card " + record.getMemberId() + " renewed until " + record.getExpiryDate();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String reportLostOrStolen(String memberId) {
        return blockCard(memberId);
    }

    @Override
    public String blockCard(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            blockListRepository.blockCard(normalized);
            try {
                memberRepository.block(normalized);
            } catch (IllegalStateException ignored) {
                // A lost card may be reported before it has a backend member row.
            }

            JCardSimGateway.CardAccessResult apduResult = cardGateway.blockIfPresent(normalized);
            return "Card " + normalized + " has been added to the Block List. " + apduResult.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String readCardStatus(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            MemberRecord record = memberRepository.find(normalized);
            StringBuilder builder = new StringBuilder();
            builder.append("Card ").append(normalized);
            builder.append(" | BackendStatus=").append(record == null ? "UNKNOWN" : record.getStatus());
            builder.append(" | Expiry=").append(record == null ? "N/A" : displayEmpty(record.getExpiryDate(), "N/A"));
            builder.append(" | Package=").append(record == null ? "N/A" : displayEmpty(record.getPackageType(), "N/A"));
            builder.append(" | Blocked=").append(blockListRepository.isBlocked(normalized));
            builder.append(" | SimulatorSession=").append(cardGateway.hasSession(normalized));
            builder.append(" | AppletActive=").append(cardGateway.isAppletActive(normalized));
            return builder.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String viewBlockedCards() {
        Map<String, String> blockedData = syncService.syncBlockList().getBlockedCardsData();
        if (blockedData.isEmpty()) {
            return "Block List is currently empty.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("--- Blocked Cards ---\n");
        for (Map.Entry<String, String> entry : blockedData.entrySet()) {
            builder.append("ID: ").append(entry.getKey())
                    .append(" | Reported At: ").append(entry.getValue()).append("\n");
        }
        return builder.toString();
    }

    @Override
    public String initializeCard(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return "ERROR: Card " + normalized + " is BLOCKED. Cannot initialize.";
            }
            cardGateway.provision(normalized);
            MemberRecord record = memberRepository.ensureInitialized(normalized, "STANDARD");
            return "Master Terminal initialized simulator card " + record.getMemberId()
                    + " with package " + record.getPackageType() + ".";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String personalizeCard(String memberId, String packageType) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return "ERROR: Card " + normalized + " is BLOCKED. Cannot personalize.";
            }
            cardGateway.provision(normalized);
            MemberRecord record = memberRepository.ensureInitialized(normalized, packageType);
            return "Card " + record.getMemberId() + " personalized with package " + record.getPackageType() + ".";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String installCertificate(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return "ERROR: Card " + normalized + " is BLOCKED. Cannot install certificate.";
            }
            cardGateway.provision(normalized);
            memberRepository.ensureInitialized(normalized, "STANDARD");
            return "Certificate installed for simulator card " + normalized + ".";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String loadIssuerData() {
        return "Issuer public data is ready for simulator card provisioning.";
    }

    @Override
    public String loadIssuerData(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (!cardGateway.hasSession(normalized)) {
                return "ERROR: Initialize simulator card " + normalized + " first.";
            }
            return "Issuer public data loaded on simulator card " + normalized + ".";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Override
    public String checkInTier1(String memberId) {
        return checkIn(memberId, false);
    }

    @Override
    public String checkInTier2(String memberId) {
        return checkIn(memberId, true);
    }

    private String checkIn(String memberId, boolean tier2) {
        try {
            String normalized = CardId.normalize(memberId);
            String policyError = validateAccessPolicy(normalized);
            if (policyError != null) {
                return "ACCESS DENIED: " + policyError;
            }

            JCardSimGateway.CardAccessResult result = tier2
                    ? cardGateway.checkInTier2(normalized, LocalDate.now())
                    : cardGateway.checkInTier1(normalized);
            return (result.isSuccess() ? "ACCESS GRANTED: " : "ACCESS DENIED: ")
                    + result.getMessage() + " [" + normalized + "]";
        } catch (Exception e) {
            return "ACCESS DENIED: " + e.getMessage();
        }
    }

    private String validateAccessPolicy(String normalized) {
        if (blockListRepository.isBlocked(normalized)) {
            return "Card is in the backend Block List.";
        }
        MemberRecord record = memberRepository.find(normalized);
        if (record == null) {
            return "Card is not present in the backend Active List.";
        }
        if (!MemberRecord.STATUS_ACTIVE.equals(record.getStatus())) {
            return "Backend status is " + record.getStatus() + ".";
        }
        if (record.isExpiredOn(LocalDate.now())) {
            return "Membership expired on " + record.getExpiryDate() + ".";
        }
        if (!cardGateway.hasSession(normalized)) {
            return "Simulator card session does not exist. Initialize it in this app run first.";
        }
        if (!cardGateway.isAppletActive(normalized)) {
            return "Simulator card is not active in this app run. Activate it in the Admin terminal.";
        }
        return null;
    }

    private String displayEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
