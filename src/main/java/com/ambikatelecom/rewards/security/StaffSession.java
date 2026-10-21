package com.ambikatelecom.rewards.security;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One signed-in browser. Only the SHA-256 of the token is stored, never the token itself, so a copy of this
 * table cannot be used to sign in. A session ends by logout, by idle timeout, by its absolute expiry, or when
 * the password changes.
 */
@Entity
@Table(name = "staff_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_sessions_token", columnNames = "token_hash"),
        indexes = {
                @Index(name = "ix_sessions_user", columnList = "user_id"),
                @Index(name = "ix_sessions_expiry", columnList = "expires_at")
        })
public class StaffSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private StaffUser user;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected StaffSession() {}

    public StaffSession(StaffUser user, String tokenHash, Instant now, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public StaffUser getUser() { return user; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
