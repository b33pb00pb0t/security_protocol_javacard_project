package frontend;

import backend.BlockListRepository;
import backend.CardGateway;
import backend.CardId;
import backend.CsvMemberRepository;
import backend.AuditLogger;
import backend.MemberRecord;
import backend.NoOpAuditLogger;
import backend.TerminalOfflineCache;
import backend.TerminalOfflineSnapshot;
import backend.TerminalSyncService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

public class ConnectedTerminalService implements TerminalService {
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final CsvMemberRepository memberRepository;
    private final BlockListRepository blockListRepository;
    private final TerminalSyncService syncService;
    private final CardGateway cardGateway;
    private final AuditLogger auditLogger;
    private final TerminalOfflineCache terminalCache;
    private String pendingPackageType = "STANDARD";

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    CardGateway cardGateway) {
        this(memberRepository, blockListRepository, syncService, cardGateway, new NoOpAuditLogger(),
                new TerminalOfflineCache());
    }

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    CardGateway cardGateway,
                                    AuditLogger auditLogger) {
        this(memberRepository, blockListRepository, syncService, cardGateway, auditLogger,
                new TerminalOfflineCache());
    }

    public ConnectedTerminalService(CsvMemberRepository memberRepository,
                                    BlockListRepository blockListRepository,
                                    TerminalSyncService syncService,
                                    CardGateway cardGateway,
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
            if (!cardGateway.hasSession(normalized) && !cardGateway.hasInitializedCard()) {
                return finish("ADMIN", normalized, "ACTIVATE", false,
                        "ERROR: Initialize a blank card from the Master terminal first.");
            }

            LocalDate expiry = parseExpiryDate(expiryDate);
            if (!cardGateway.hasSession(normalized)
                    || !cardGateway.isAppletActive(normalized)
                    || "HARDWARE".equals(cardGateway.getGatewayName())) {
                cardGateway.activate(normalized, LocalDate.now(), expiry);
            }
            // Phone and package details are backend policy data; the applet activation
            // contract contains only member ID, current date, and expiry date.
            memberRepository.ensureInitialized(normalized, pendingPackageType);
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

            CardGateway.CardAccessResult apduResult = cardGateway.blockIfPresent(normalized);
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
            builder.append(" | Gateway=").append(cardGateway.getGatewayName());
            builder.append(" | CardSession=").append(cardGateway.hasSession(normalized));
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
    public String initializeCard() {
        try {
            pendingPackageType = "STANDARD";
            cardGateway.provision();
            return finish("MASTER", "", "INITIALIZE", true,
                    "Master Terminal initialized " + cardGateway.getGatewayName().toLowerCase()
                            + " blank card. Assign the member ID in the Admin terminal.");
        } catch (Exception e) {
            return finish("MASTER", "", "INITIALIZE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String resetCard() {
        try {
            CardGateway.CardAccessResult result = cardGateway.resetCard();
            if (result.isSuccess()) {
                pendingPackageType = "STANDARD";
            }
            return finish("MASTER", "", "RESET_CARD", result.isSuccess(),
                    (result.isSuccess() ? "RESET COMPLETE: " : "RESET FAILED: ") + result.getMessage());
        } catch (Exception e) {
            return finish("MASTER", "", "RESET_CARD", false, "RESET FAILED: " + e.getMessage());
        }
    }

    @Override
    public String personalizeCard(String packageType) {
        try {
            cardGateway.provision();
            pendingPackageType = normalizePackage(packageType);
            return finish("MASTER", "", "PERSONALIZE", true,
                    "Blank card personalized with pending package " + pendingPackageType
                            + ". Member ID will be assigned in the Admin terminal.");
        } catch (Exception e) {
            return finish("MASTER", "", "PERSONALIZE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String installCertificate() {
        try {
            cardGateway.provision();
            return finish("MASTER", "", "INSTALL_CERTIFICATE", true,
                    "Certificate installed for " + cardGateway.getGatewayName().toLowerCase()
                            + " blank card identity.");
        } catch (Exception e) {
            return finish("MASTER", "", "INSTALL_CERTIFICATE", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String loadIssuerData() {
        try {
            if (!cardGateway.hasInitializedCard()) {
                return finish("MASTER", "", "LOAD_ISSUER_DATA", false,
                        "ERROR: Initialize/select a blank card first.");
            }
            return finish("MASTER", "", "LOAD_ISSUER_DATA", true,
                    "Issuer public data loaded on " + cardGateway.getGatewayName().toLowerCase()
                            + " blank card.");
        } catch (Exception e) {
            return finish("MASTER", "", "LOAD_ISSUER_DATA", false, "ERROR: " + e.getMessage());
        }
    }

    @Override
    public String readInitializedCardStatus() {
        try {
            StringBuilder builder = new StringBuilder();
            builder.append("BlankCardInitialized=").append(cardGateway.hasInitializedCard());
            builder.append(" | Gateway=").append(cardGateway.getGatewayName());
            builder.append(" | PendingPackage=").append(pendingPackageType);
            builder.append(" | AccessCache=").append(terminalCache.getSnapshot().isSynced() ? "SYNCED" : "NOT_SYNCED");
            return finish("MASTER", "", "READ_INITIALIZED_CARD_STATUS", true, builder.toString());
        } catch (Exception e) {
            return finish("MASTER", "", "READ_INITIALIZED_CARD_STATUS", false, "ERROR: " + e.getMessage());
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

            CardGateway.CardAccessResult result = tier2
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
            return cardGateway.getGatewayName() + " card session does not exist. Initialize/select the card first.";
        }
        if (!cardGateway.isAppletActive(normalized)) {
            return cardGateway.getGatewayName() + " card is not active. Activate it in the Admin terminal.";
        }
        return null;
    }

    private String displayEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private LocalDate parseExpiryDate(String expiryDate) {
        if (expiryDate == null || expiryDate.trim().isEmpty()) {
            throw new IllegalArgumentException("Expiry date cannot be empty");
        }
        try {
            return LocalDate.parse(expiryDate.trim(), EXPIRY_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expiry date must use YYYYMMDD", e);
        }
    }

    private String normalizePackage(String packageType) {
        return packageType == null || packageType.trim().isEmpty()
                ? "STANDARD"
                : packageType.trim().toUpperCase();
    }

    private String finish(String terminal, String memberId, String action, boolean success, String message) {
        auditLogger.log(terminal, memberId, action, success, message);
        return message;
    }
}
