package frontend;

public interface TerminalService {
    String activateCard(String memberId, String expiryDate);

    String activateCard(String memberId, String expiryDate, String phoneNumber);

    String deactivateCard(String memberId);

    String renewMembership(String memberId, String newExpiryDate);

    String reportLostOrStolen(String memberId);

    String blockCard(String memberId);

    String readCardStatus(String memberId);

    String viewBlockedCards();

    String initializeCard();

    String resetCard();

    String personalizeCard(String packageType);

    String installCertificate();

    String loadIssuerData();

    String readInitializedCardStatus();

    String syncTerminals();

    String checkInTier1(String memberId);

    String checkInTier2(String memberId);
}
