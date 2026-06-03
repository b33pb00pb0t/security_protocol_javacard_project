package backend;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class MemberRecord {
    public static final String STATUS_INITIALIZED = "INITIALIZED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_BLOCKED = "BLOCKED";

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final String memberId;
    private final String status;
    private final String expiryDate;
    private final String packageType;
    private final String phone;
    private final String createdAt;
    private final String updatedAt;

    public MemberRecord(String memberId, String status, String expiryDate, String packageType,
                        String phone, String createdAt, String updatedAt) {
        this.memberId = CardId.normalize(memberId);
        this.status = emptyToDefault(status, STATUS_INITIALIZED).toUpperCase();
        this.expiryDate = emptyToDefault(expiryDate, "");
        this.packageType = emptyToDefault(packageType, "STANDARD").toUpperCase();
        this.phone = emptyToDefault(phone, "");
        this.createdAt = emptyToDefault(createdAt, "");
        this.updatedAt = emptyToDefault(updatedAt, "");
    }

    public String getMemberId() {
        return memberId;
    }

    public String getStatus() {
        return status;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public String getPackageType() {
        return packageType;
    }

    public String getPhone() {
        return phone;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActiveOn(LocalDate date) {
        return STATUS_ACTIVE.equals(status) && !isExpiredOn(date);
    }

    public boolean isExpiredOn(LocalDate date) {
        if (expiryDate.trim().isEmpty()) {
            return false;
        }
        return parseExpiry(expiryDate).isBefore(date);
    }

    public MemberRecord withStatus(String newStatus, String timestamp) {
        return new MemberRecord(memberId, newStatus, expiryDate, packageType, phone, createdAt, timestamp);
    }

    public MemberRecord withPackageType(String newPackageType, String timestamp) {
        return new MemberRecord(memberId, status, expiryDate, newPackageType, phone, createdAt, timestamp);
    }

    public MemberRecord withActivation(String newExpiryDate, String newPhone, String timestamp) {
        validateExpiry(newExpiryDate);
        return new MemberRecord(memberId, STATUS_ACTIVE, newExpiryDate, packageType, newPhone, createdAt, timestamp);
    }

    public MemberRecord withRenewal(String newExpiryDate, String timestamp) {
        validateExpiry(newExpiryDate);
        return new MemberRecord(memberId, STATUS_ACTIVE, newExpiryDate, packageType, phone, createdAt, timestamp);
    }

    static void validateExpiry(String expiryDate) {
        if (expiryDate == null || expiryDate.trim().isEmpty()) {
            throw new IllegalArgumentException("Expiry date cannot be empty");
        }
        parseExpiry(expiryDate.trim());
    }

    private static LocalDate parseExpiry(String expiryDate) {
        try {
            return LocalDate.parse(expiryDate, EXPIRY_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expiry date must use YYYYMMDD", e);
        }
    }

    private static String emptyToDefault(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }
}
