package com.ambikatelecom.rewards.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(nullable = false)
    private boolean active = true;

    /** BCrypt hash. Null until a password is set; an account with no hash cannot sign in. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** True for a bootstrap password: the only thing the account may do is choose a new one. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_user_roles", columnNames = {"user_id", "role"}))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<StaffRole> roles = EnumSet.noneOf(StaffRole.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffUser() {}

    public StaffUser(String email) {
        this.email = email.toLowerCase();
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    /** First password, set by the bootstrap from an environment setting. Forces a change at first sign-in. */
    public void setInitialPassword(String hash, Instant now) {
        this.passwordHash = hash;
        this.mustChangePassword = true;
        this.passwordChangedAt = now;
    }

    public void changePassword(String hash, Instant now) {
        this.passwordHash = hash;
        this.mustChangePassword = false;
        this.passwordChangedAt = now;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public boolean isActive() { return active; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Set<StaffRole> getRoles() { return roles; }
}
