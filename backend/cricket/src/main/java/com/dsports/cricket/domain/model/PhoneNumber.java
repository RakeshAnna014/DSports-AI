package com.dsports.cricket.domain.model;

import static com.dsports.cricket.domain.model.Rules.require;

/**
 * Normalises a phone number so the same number always compares equal: spaces, dashes and brackets are dropped; a bare 10-digit number is read as
 * Indian (+91); a number with a country code needs the leading +, or 91 in front of 10 digits. The result is "+" followed by 8 to 15 digits.
 * A phone number here is a search hint for claiming a guest profile, never proof of identity.
 */
public final class PhoneNumber {

    private PhoneNumber() {
    }

    /** @return the normalised number, or null when {@code raw} is null or blank. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var cleaned = raw.trim().replaceAll("[\\s\\-().]", "");
        String digits;
        if (cleaned.startsWith("+")) {
            digits = cleaned.substring(1);
        } else if (cleaned.length() == 10) {
            digits = "91" + cleaned;
        } else if (cleaned.length() == 12 && cleaned.startsWith("91")) {
            digits = cleaned;
        } else {
            digits = null;
        }
        require(digits != null && digits.matches("[0-9]{8,15}"), "Enter a phone number with 10 digits, or with a country code starting with +");
        return "+" + digits;
    }

    /** "Arjun Kumar" -> "Arjun K.": enough to recognise yourself, not enough to harvest a name from a phone number. */
    public static String maskedName(String fullName) {
        var parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
