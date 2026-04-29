package com.sports.recreation.frontend;

public class MockTerminalService implements TerminalService {

    @Override
    public String activateCard(String memberId, String expiryDate) {
        return "Card " + memberId + " activated until " + expiryDate;
    }

    @Override
    public String deactivateCard(String memberId) {
        return "Card " + memberId + " deactivated.";
    }

    @Override
    public String renewMembership(String memberId, String newExpiryDate) {
        return "Membership for card " + memberId + " renewed until " + newExpiryDate;
    }

    @Override
    public String reportLostOrStolen(String memberId) {
        return "Card " + memberId + " reported as lost/stolen and added to Block List.";
    }

    @Override
    public String blockCard(String memberId) {
        return "Card " + memberId + " blocked.";
    }

    @Override
    public String readCardStatus(String memberId) {
        return "Card status for " + memberId + ": ACTIVE / mock response.";
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