package frontend;

import backend.BlockListRepository;
import backend.CardId;
import backend.CsvMemberRepository;
import backend.AuditLogger;
import backend.JCardSimGateway;
import backend.MemberRecord;
import backend.NoOpAuditLogger;
import backend.TerminalOfflineCache;
import backend.TerminalOfflineSnapshot;
import backend.TerminalSyncService;

import java.time.LocalDate;
import java.util.Map;

public class ConnectedTerminalService implements TerminalService {
    private final CsvMemberRepository memberRepository;
    private final BlockListRepository blockListRepository;
    private final TerminalSyncService syncService;
    private final JCardSimGateway cardGateway;
    private final AuditLogger auditLogger;
    private final TerminalOfflineCache terminalCache;

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    JCardSimGateway cardGateway) {
        this(memberRepository, blockListRepository, syncService, cardGateway, new NoOpAuditLogger(),
                new TerminalOfflineCache());
    }

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    JCardSimGateway cardGateway,
                                    AuditLogger auditLogger) {
        this(memberRepository, blockListRepository, syncService, cardGateway, auditLogger,
                new TerminalOfflineCache());
    }

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    JCardSimGateway cardGateway,
                                    AuditLogger auditLogger,
                                    TerminalOfflineCache terminalCache) {
        this.memberRepository = memberRepository;
        this.blockListRepository = blockListRepository;
        this.syncService = syncService;
        this.cardGateway = cardGateway;
        this.auditLogger = auditLogger;
        this.terminalCache = terminalCache;
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
                return finish("ADMIN", normalized, "ACTIVATE", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot activate.");
            }
            if (!cardGateway.hasSession(normalized)) {
                return finish("ADMIN", normalized, "ACTIVATE", false,
                        "ERROR: Initialize simulator card " + normalized + " from the Master terminal first.");
            }

            if (!cardGateway.isAppletActive(normalized)) {
                cardGateway.activate(normalized, LocalDate.now());
            }
            MemberRecord record = memberRepository.activate(normalized, expiryDate, phoneNumber);
            return finish("ADMIN", normalized, "ACTIVATE", true,
                    "Card " + record.getMemberId() + " activated until " + record.getExpiryDate()
                            + ". Phone linked: " + displayEmpty(record.getPhone(), "N/A"));
        } catch (Exception e) {
            return finish("ADMIN", memberId, "ACTIVATE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String deactivateCard(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            MemberRecord record = memberRepository.deactivate(normalized);
            return finish("ADMIN", normalized, "DEACTIVATE", true,
                    "Card " + record.getMemberId() + " deactivated in backend policy.");
        } catch (Exception e) {
            return finish("ADMIN", memberId, "DEACTIVATE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String renewMembership(String memberId, String newExpiryDate) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return finish("ADMIN", normalized, "RENEW", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot renew.");
            }
            MemberRecord record = memberRepository.renew(normalized, newExpiryDate);
            return finish("ADMIN", normalized, "RENEW", true,
                    "Membership for card " + record.getMemberId() + " renewed until " + record.getExpiryDate());
        } catch (Exception e) {
            return finish("ADMIN", memberId, "RENEW", false, "ERROR: " + e.getMessage());
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
            return finish("ADMIN", normalized, "BLOCK", apduResult.isSuccess(),
                    "Card " + normalized + " has been added to the Block List. " + apduResult.getMessage());
        } catch (Exception e) {
            return finish("ADMIN", memberId, "BLOCK", false, "ERROR: " + e.getMessage());
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
            builder.append(" | AccessCache=").append(terminalCache.getSnapshot().isSynced() ? "SYNCED" : "NOT_SYNCED");
            return finish("ADMIN", normalized, "READ_STATUS", true, builder.toString());
        } catch (Exception e) {
            return finish("ADMIN", memberId, "READ_STATUS", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String viewBlockedCards() {
        Map<String, String> blockedData = syncService.syncBlockList().getBlockedCardsData();
        if (blockedData.isEmpty()) {
            return finish("ADMIN", "", "VIEW_BLOCKED_CARDS", true, "Block List is currently empty.");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("--- Blocked Cards ---\n");
        for (Map.Entry<String, String> entry : blockedData.entrySet()) {
            builder.append("ID: ").append(entry.getKey())
                    .append(" | Reported At: ").append(entry.getValue()).append("\n");
        }
        return finish("ADMIN", "", "VIEW_BLOCKED_CARDS", true, builder.toString());
    }

    @Override
    public String initializeCard(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return finish("MASTER", normalized, "INITIALIZE", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot initialize.");
            }
            cardGateway.provision(normalized);
            MemberRecord record = memberRepository.ensureInitialized(normalized, "STANDARD");
            return finish("MASTER", normalized, "INITIALIZE", true,
                    "Master Terminal initialized simulator card " + record.getMemberId()
                            + " with package " + record.getPackageType() + ".");
        } catch (Exception e) {
            return finish("MASTER", memberId, "INITIALIZE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String personalizeCard(String memberId, String packageType) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return finish("MASTER", normalized, "PERSONALIZE", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot personalize.");
            }
            cardGateway.provision(normalized);
            MemberRecord record = memberRepository.ensureInitialized(normalized, packageType);
            return finish("MASTER", normalized, "PERSONALIZE", true,
                    "Card " + record.getMemberId() + " personalized with package " + record.getPackageType() + ".");
        } catch (Exception e) {
            return finish("MASTER", memberId, "PERSONALIZE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String installCertificate(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (blockListRepository.isBlocked(normalized)) {
                return finish("MASTER", normalized, "INSTALL_CERTIFICATE", false,
                        "ERROR: Card " + normalized + " is BLOCKED. Cannot install certificate.");
            }
            cardGateway.provision(normalized);
            memberRepository.ensureInitialized(normalized, "STANDARD");
            return finish("MASTER", normalized, "INSTALL_CERTIFICATE", true,
                    "Certificate installed for simulator card " + normalized + ".");
        } catch (Exception e) {
            return finish("MASTER", memberId, "INSTALL_CERTIFICATE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String loadIssuerData() {
        return finish("MASTER", "", "LOAD_ISSUER_DATA", true,
                "Issuer public data is ready for simulator card provisioning.");
    }

    @Override
    public String loadIssuerData(String memberId) {
        try {
            String normalized = CardId.normalize(memberId);
            if (!cardGateway.hasSession(normalized)) {
                return finish("MASTER", normalized, "LOAD_ISSUER_DATA", false,
                        "ERROR: Initialize simulator card " + normalized + " first.");
            }
            return finish("MASTER", normalized, "LOAD_ISSUER_DATA", true,
                    "Issuer public data loaded on simulator card " + normalized + ".");
        } catch (Exception e) {
            return finish("MASTER", memberId, "LOAD_ISSUER_DATA", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String syncTerminals() {
        try {
            TerminalOfflineSnapshot snapshot = terminalCache.sync(memberRepository.findAll(), syncService.syncBlockList());
            return finish("ACCESS", "", "SYNC_TERMINALS", true, snapshot.describe());
        } catch (Exception e) {
            return finish("ACCESS", "", "SYNC_TERMINALS", false, "ERROR: " + e.getMessage());
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
                return finish("ACCESS", normalized, tier2 ? "CHECK_IN_T2" : "CHECK_IN_T1", false,
                        "ACCESS DENIED: " + policyError);
            }

            JCardSimGateway.CardAccessResult result = tier2
                    ? cardGateway.checkInTier2(normalized, LocalDate.now())
                    : cardGateway.checkInTier1(normalized);
            return finish("ACCESS", normalized, tier2 ? "CHECK_IN_T2" : "CHECK_IN_T1", result.isSuccess(),
                    (result.isSuccess() ? "ACCESS GRANTED: " : "ACCESS DENIED: ")
                            + result.getMessage() + " [" + normalized + "]");
        } catch (Exception e) {
            return finish("ACCESS", memberId, tier2 ? "CHECK_IN_T2" : "CHECK_IN_T1", false,
                    "ACCESS DENIED: " + e.getMessage());
        }
    }

    private String validateAccessPolicy(String normalized) {
        TerminalOfflineSnapshot snapshot = terminalCache.getSnapshot();
        if (!snapshot.isSynced()) {
            return "Access terminal cache is not synced. Press Sync Terminals first.";
        }
        if (snapshot.isBlocked(normalized)) {
            return "Card is in the access terminal Block List snapshot.";
        }
        MemberRecord record = snapshot.findActiveMember(normalized);
        if (record == null) {
            return "Card is not present in the access terminal Active List snapshot.";
        }
        if (record.isExpiredOn(LocalDate.now())) {
            return "Cached membership expired on " + record.getExpiryDate() + ".";
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

    private String finish(String terminal, String memberId, String action, boolean success, String message) {
        auditLogger.log(terminal, memberId, action, success, message);
        return message;
    }
}
