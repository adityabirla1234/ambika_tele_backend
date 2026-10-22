package com.ambikatelecom.rewards.security;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff sign-in with email and password.
 *
 * - Passwords are stored as BCrypt hashes only.
 * - Every failed sign-in looks the same to the caller (unknown email, wrong password, inactive, locked), and an
 *   unknown email still pays for one BCrypt comparison, so response time does not reveal which emails exist.
 * - After N wrong passwords the account is locked for a while. Counters change with atomic UPDATEs.
 * - A successful sign-in returns a random 256-bit token. Only its SHA-256 is stored.
 * - This class is deliberately NOT transactional as a whole: a failed sign-in must still be recorded, and an
 *   exception must not roll that record back. Multi-step writes use a TransactionTemplate where they must be atomic.
 */
@Service
public class StaffAuthService {

    public record Profile(String email, List<String> roles, boolean mustChangePassword) {}

    public record LoginResult(String token, Instant expiresAt, Profile profile) {}

    /** What the request filter learns about a valid session. */
    public record SessionPrincipal(Long sessionId, Long userId, String email, Set<StaffRole> roles,
                                   boolean mustChangePassword) {}

    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,100}");
    /** Keep the audit "actor" column (80 characters) from overflowing. */
    public static final int MAX_EMAIL_LENGTH = 80;
    private static final Duration TOUCH_EVERY = Duration.ofSeconds(60);
    private static final Duration CLEANUP_EVERY = Duration.ofHours(1);
    private final java.util.concurrent.atomic.AtomicLong lastCleanupMillis = new java.util.concurrent.atomic.AtomicLong(0);

    private final StaffUserRepository users;
    private final StaffSessionRepository sessions;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;
    private final Duration idleTimeout;
    private final Duration maxAge;
    private final int maxFailed;
    private final Duration lockout;
    private final SecureRandom random = new SecureRandom();

    public StaffAuthService(StaffUserRepository users, StaffSessionRepository sessions, AuditService audit,
                            PlatformTransactionManager txManager,
                            @Value("${app.auth.bcrypt-strength:12}") int bcryptStrength,
                            @Value("${app.auth.session-idle-minutes:30}") int idleMinutes,
                            @Value("${app.auth.session-max-hours:12}") int maxHours,
                            @Value("${app.auth.max-failed-logins:5}") int maxFailed,
                            @Value("${app.auth.lockout-minutes:15}") int lockoutMinutes) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.encoder = new BCryptPasswordEncoder(Math.max(10, bcryptStrength));
        this.dummyHash = encoder.encode("timing-equalizer-not-a-real-password");
        this.idleTimeout = Duration.ofMinutes(idleMinutes);
        this.maxAge = Duration.ofHours(maxHours);
        this.maxFailed = maxFailed;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
    }

    public String hashPassword(String raw) {
        return encoder.encode(raw);
    }

    public static String normalizeEmail(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toLowerCase();
        return e.length() <= MAX_EMAIL_LENGTH && EMAIL_SHAPE.matcher(e).matches() ? e : null;
    }

    // ------------------------------------------------------------------ sign in

    public LoginResult login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        StaffUser user = email == null ? null : users.findByEmail(email).orElse(null);
        Instant now = Instant.now();
        boolean usable = user != null && user.isActive() && user.getPasswordHash() != null
                && (user.getLockedUntil() == null || user.getLockedUntil().isBefore(now));

        boolean passwordOk = false;
        if (usable && !PasswordPolicy.tooLongForBcrypt(password)) {
            passwordOk = encoder.matches(password, user.getPasswordHash());
        } else {
            encoder.matches("timing-equalizer", dummyHash); // same cost as a real check
        }

        if (!passwordOk) {
            if (usable) {
                recordFailure(user);
                audit.record("STAFF_LOGIN_FAILED", "STAFF_USER", user.getId(), user.getEmail(), null);
            }
            // Unknown, inactive and locked accounts are not audited one attempt at a time: anyone can send those,
            // and the per-IP limiter already bounds them.
            throw invalidLogin();
        }

        String token = newToken();
        Instant expires = now.plus(maxAge);
        final StaffUser signedIn = user;
        // One transaction for the whole success path: one commit instead of four, which matters because every
        // round trip to the (remote) database costs real time.
        tx.executeWithoutResult(status -> {
            users.recordLogin(signedIn.getId(), now);
            sessions.save(new StaffSession(signedIn, sha256(token), now, expires));
            audit.record("STAFF_LOGIN", "STAFF_USER", signedIn.getId(), signedIn.getEmail(), null);
        });
        cleanUpOldSessionsOccasionally(now);
        return new LoginResult(token, expires, profileOf(user));
    }

    /** Housekeeping at most once an hour, not on every sign-in. Failure here must never break a sign-in. */
    private void cleanUpOldSessionsOccasionally(Instant now) {
        long last = lastCleanupMillis.get();
        long nowMs = now.toEpochMilli();
        if (nowMs - last < CLEANUP_EVERY.toMillis() || !lastCleanupMillis.compareAndSet(last, nowMs)) return;
        try {
            sessions.deleteExpiredBefore(now.minus(Duration.ofDays(1)));
        } catch (RuntimeException ignored) {
            // retried at the next hourly sign-in
        }
    }

    private void recordFailure(StaffUser user) {
        users.incrementFailed(user.getId());
        if (users.lockIfOver(user.getId(), maxFailed, Instant.now().plus(lockout)) > 0) {
            audit.record("STAFF_ACCOUNT_LOCKED", "STAFF_USER", user.getId(), user.getEmail(), null);
        }
    }

    private static ApiException invalidLogin() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN",
                "Email or password is not correct, or the account is temporarily locked. Please try again in a few minutes.");
    }

    // ------------------------------------------------------------------ per-request check

    /** Called for every request that carries a token. Returns empty for anything not valid right now. */
    public Optional<SessionPrincipal> authenticate(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) return Optional.empty();
        StaffSession s = sessions.findByTokenHash(sha256(token)).orElse(null);
        if (s == null || s.getRevokedAt() != null) return Optional.empty();
        Instant now = Instant.now();
        if (!s.getExpiresAt().isAfter(now)) return Optional.empty();
        if (!s.getLastSeenAt().plus(idleTimeout).isAfter(now)) return Optional.empty();
        StaffUser u = s.getUser();
        if (!u.isActive()) return Optional.empty(); // switching a user off ends their sessions at once
        if (!s.getLastSeenAt().plus(TOUCH_EVERY).isAfter(now)) sessions.touch(s.getId(), now);
        return Optional.of(new SessionPrincipal(s.getId(), u.getId(), u.getEmail(), Set.copyOf(u.getRoles()),
                u.isMustChangePassword()));
    }

    // ------------------------------------------------------------------ sign out and password change

    public void logout(String token, String email) {
        if (token == null) return;
        // One transaction (one commit) for the revoke and its audit row.
        tx.executeWithoutResult(status -> {
            if (sessions.revokeByTokenHash(sha256(token), Instant.now()) > 0) {
                audit.record("STAFF_LOGOUT", "STAFF_USER", null, email, null);
            }
        });
    }

    public void changePassword(SessionPrincipal who, String currentPassword, String newPassword) {
        StaffUser user = users.findById(who.userId()).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again."));
        Instant now = Instant.now();
        ApiException wrongCurrent = new ApiException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD",
                "Your current password is not correct.", "currentPassword");

        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        if (locked || user.getPasswordHash() == null || PasswordPolicy.tooLongForBcrypt(currentPassword)
                || !encoder.matches(currentPassword, user.getPasswordHash())) {
            if (!locked) recordFailure(user); // guessing the current password through a stolen session counts too
            throw wrongCurrent;
        }
        Optional<String> problem = PasswordPolicy.check(newPassword, user.getEmail());
        if (problem.isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", problem.get(), "newPassword");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "Choose a password different from your current one.", "newPassword");
        }

        String newHash = encoder.encode(newPassword);
        tx.executeWithoutResult(status -> {
            StaffUser u = users.findById(who.userId()).orElseThrow();
            u.changePassword(newHash, now);
            users.saveAndFlush(u); // write the new hash NOW: the bulk update below clears unflushed changes
            sessions.revokeOthers(u.getId(), who.sessionId(), now); // every other browser is signed out
        });
        users.clearFailures(who.userId());
        audit.record("STAFF_PASSWORD_CHANGED", "STAFF_USER", who.userId(), who.email(), null);
    }

    // ------------------------------------------------------------------ helpers

    public static Profile profileOf(StaffUser u) {
        return new Profile(u.getEmail(), u.getRoles().stream().map(Enum::name).sorted().toList(),
                u.isMustChangePassword());
    }

    public static Profile profileOf(SessionPrincipal p) {
        return new Profile(p.email(), p.roles().stream().map(Enum::name).sorted().toList(), p.mustChangePassword());
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
