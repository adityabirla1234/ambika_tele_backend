package com.ambikatelecom.rewards.security;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Rules for staff passwords. Length matters more than symbols, so there is no "must contain a digit" rule.
 * Not checked: whether the password appears in known data breaches (that needs an outside service).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 06;
    /** BCrypt only reads the first 72 bytes, so anything longer would be silently cut. We refuse it instead. */
    public static final int MAX_BYTES = 72;

    private static final String[] TOO_EASY = {"password", "passw0rd", "qwerty", "letmein", "welcome", "iloveyou",
            "123456", "abc123", "admin", "ambika"};

    private PasswordPolicy() {}

    public static boolean tooLongForBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /** Returns the problem in plain words, or empty when the password is acceptable. */
    public static Optional<String> check(String password, String email) {
        if (password == null || password.isBlank()) return Optional.of("Enter a password.");
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of("Use at least " + MIN_LENGTH + " characters.");
        }
        if (tooLongForBcrypt(password)) {
            return Optional.of("That password is too long. Use at most " + MAX_BYTES + " bytes (some symbols count as more than one).");
        }
        String lower = password.toLowerCase();
        if (email != null) {
            String local = email.toLowerCase();
            int at = local.indexOf('@');
            if (at > 0) local = local.substring(0, at);
            if (local.length() >= 4 && lower.contains(local)) {
                return Optional.of("Do not use your email name in your password.");
            }
        }
        for (String bad : TOO_EASY) {
            if (lower.contains(bad)) return Optional.of("That password is too easy to guess. Choose something less common.");
        }
        if (password.codePoints().distinct().count() < 5) {
            return Optional.of("That password repeats too much. Use more different characters.");
        }
        return Optional.empty();
    }
}
