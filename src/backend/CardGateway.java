package backend;

import java.time.LocalDate;

/**
 * Transport boundary used by the frontend service so the same workflow can
 * target either an in-memory JCardSim card or a physical JavaCard.
 */
public interface CardGateway {
    String getGatewayName();

    boolean hasSession(String memberId);

    boolean hasInitializedCard();

    boolean isAppletActive(String memberId);

    void provision();

    CardAccessResult resetCard();

    void activate(String memberId, LocalDate currentDate, LocalDate expiryDate);

    CardAccessResult blockIfPresent(String memberId);

    CardAccessResult checkInTier1(String memberId);

    CardAccessResult checkInTier2(String memberId, LocalDate currentDate);

    class CardAccessResult {
        private final boolean success;
        private final String message;

        protected CardAccessResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static CardAccessResult success(String message) {
            return new CardAccessResult(true, message);
        }

        public static CardAccessResult denied(String message) {
            return new CardAccessResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
