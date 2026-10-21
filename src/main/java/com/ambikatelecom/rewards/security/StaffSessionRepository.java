package com.ambikatelecom.rewards.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StaffSessionRepository extends JpaRepository<StaffSession, Long> {

    @Query("select s from StaffSession s join fetch s.user where s.tokenHash = :hash")
    Optional<StaffSession> findByTokenHash(@Param("hash") String hash);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffSession s set s.lastSeenAt = :now where s.id = :id")
    int touch(@Param("id") Long id, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update StaffSession s set s.revokedAt = :now where s.tokenHash = :hash and s.revokedAt is null")
    int revokeByTokenHash(@Param("hash") String hash, @Param("now") Instant now);

    /** After a password change: every other browser is signed out, this one stays. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StaffSession s set s.revokedAt = :now "
            + "where s.user.id = :userId and s.id <> :keepId and s.revokedAt is null")
    int revokeOthers(@Param("userId") Long userId, @Param("keepId") Long keepId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from StaffSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
