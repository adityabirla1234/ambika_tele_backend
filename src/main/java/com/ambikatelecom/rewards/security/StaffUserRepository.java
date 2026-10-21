package com.ambikatelecom.rewards.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByEmail(String email);

    // The counters are changed with single UPDATE statements, so two simultaneous wrong guesses both count.
    // Each runs in its own transaction on purpose: a failed sign-in must still be recorded.

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffUser u set u.failedAttempts = u.failedAttempts + 1 where u.id = :id")
    int incrementFailed(@Param("id") Long id);

    /** Locks the account once the failure count reaches the limit. Returns 1 when it locked. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffUser u set u.lockedUntil = :until, u.failedAttempts = 0 "
            + "where u.id = :id and u.failedAttempts >= :max")
    int lockIfOver(@Param("id") Long id, @Param("max") int max, @Param("until") Instant until);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffUser u set u.failedAttempts = 0, u.lockedUntil = null, u.lastLoginAt = :now where u.id = :id")
    int recordLogin(@Param("id") Long id, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffUser u set u.failedAttempts = 0, u.lockedUntil = null where u.id = :id")
    int clearFailures(@Param("id") Long id);
}
