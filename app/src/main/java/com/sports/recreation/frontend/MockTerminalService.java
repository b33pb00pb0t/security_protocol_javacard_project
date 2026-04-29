package com.sports.recreation.frontend;

import com.sports.recreation.backend.MembershipAdminService;
import com.sports.recreation.backend.TerminalSyncService;
import java.util.Set;

public class MockTerminalService implements TerminalService {

    private final MembershipAdminService adminService;
    private final TerminalSyncService syncService;

    public MockTerminalService(MembershipAdminService adminService, TerminalSyncService syncService) {
        this.adminService = adminService;
        this.syncService = syncService;
    }

    @Override
    public String activateCard(String memberId, String expiryDate) {
        if (adminService.isCardBlocked(memberId)) {
            return "ERROR: Card " + memberId + " is BLOCKED. Cannot activate.";
        }
        return "Card " + memberId + " activated until " + expiryDate;
    }

    @Override
    public String deactivateCard(String memberId) {
        if (adminService.isCardBlocked(memberId)) {
            return "ERROR: Card " + memberId + " is BLOCKED. Cannot deactivate.";
        }
        return "Card " + memberId + " deactivated.";
    }

    @Override
    public String renewMembership(String memberId, String newExpiryDate) {
        if (adminService.isCardBlocked(memberId)) {
            return "ERROR: Card " + memberId + " is BLOCKED. Cannot renew.";
        }
        return "Membership for card " + memberId + " renewed until " + newExpiryDate;
    }

    @Override
    public String reportLostOrStolen(String memberId) {
        return adminService.reportLostOrStolenCard(memberId);
    }

    @Override
    public String blockCard(String memberId) {
        return adminService.reportLostOrStolenCard(memberId);
    }

    @Override
    public String readCardStatus(String memberId) {
        if (adminService.isCardBlocked(memberId)) {
            return "Card status for " + memberId + ": BLOCKED by Admin/Backend.";
        }
        return "Card status for " + memberId + ": ACTIVE / mock response.";
    }

    @Override
    public String viewBlockedCards() {
        java.util.Map<String, String> blockedData = syncService.syncBlockList().getBlockedCardsData();
        if (blockedData.isEmpty()) {
            return "Block List is currently empty.";
        }
        
        StringBuilder b = new StringBuilder();
        b.append("--- Blocked Cards ---\n");
        for (java.util.Map.Entry<String, String> entry : blockedData.entrySet()) {
            b.append("ID: ").append(entry.getKey()).append(" | Reported At: ").append(entry.getValue()).append("\n");
        }
        return b.toString();
    }

    @Override
    public String initializeCard(String memberId) {
        return "Master Terminal initialized card for member " + memberId;
    }

    @Override
    public String personalizeCard(String memberId, String packageType) {
        return "Card personalized for member " + memberId + " with package " + packageType;
    }

    @Override
    public String installCertificate(String memberId) {
        return "Certificate installed for card " + memberId;
    }

    @Override
    public String loadIssuerData() {
        return "Issuer public data loaded.";
    }
}