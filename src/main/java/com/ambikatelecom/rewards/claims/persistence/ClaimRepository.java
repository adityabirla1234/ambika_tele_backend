package com.ambikatelecom.rewards.claims.persistence;

import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.domain.ClaimStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    boolean existsByDeviceKey(String deviceKey);
    boolean existsByDeviceKeyIn(Collection<String> deviceKeys);
    boolean existsByDeviceKeyInAndIdNot(Collection<String> deviceKeys, Long id);
    long countByCustomerIdAndPersonalDataErasedAtIsNull(Long customerId);

    /** Claims still on an old key version or an old encryption key, for KeyRotationRunner (keyset by id). Skips erased claims. */
    @Query("select c.id from Claim c where c.id > :lastId and c.personalDataErasedAt is null "
            + "and (c.deviceKeyVersion <> :hmacVersion or c.deviceIdentifierEnc not like :encPrefix) order by c.id")
    List<Long> findRotationCandidateIds(@Param("lastId") Long lastId, @Param("hmacVersion") int hmacVersion,
                                        @Param("encPrefix") String encPrefix, Pageable page);

    /** How many claims use each HMAC key version (erased claims keep theirs). */
    @Query("select c.deviceKeyVersion, count(c) from Claim c group by c.deviceKeyVersion")
    List<Object[]> countByKeyVersion();

    /**
     * Claims whose personal data is due for erasure: older than the cutoff, not erased yet, and not an approved
     * reward still waiting to be paid (that customer may still need to be reached).
     */
    @Query("select c.id from Claim c where c.id > :lastId and c.personalDataErasedAt is null and c.submittedAt < :cutoff "
            + "and (c.status <> com.ambikatelecom.rewards.claims.domain.ClaimStatus.APPROVED "
            + "or exists (select r.id from Reward r where r.claim = c and r.state = com.ambikatelecom.rewards.rewards.domain.RewardState.REDEEMED)) "
            + "order by c.id")
    List<Long> findRetentionCandidateIds(@Param("lastId") Long lastId, @Param("cutoff") java.time.Instant cutoff, Pageable page);
    List<Claim> findByDeviceKeyIn(Collection<String> deviceKeys);
    boolean existsByClaimReference(String claimReference);
    long countByInvoiceNumberAndIdNot(String invoiceNumber, Long id);

    /** SELECT ... FOR UPDATE: serializes approve/reject/reopen/mark-paid on one claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id")
    Optional<Claim> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "select c from Claim c join fetch c.customer cu join fetch c.product p "
            + "where c.status in :statuses and (lower(c.claimReference) like :q or lower(cu.name) like :q "
            + "or lower(coalesce(c.invoiceNumber, '')) like :q or cu.phoneE164 like :q)",
            countQuery = "select count(c) from Claim c join c.customer cu "
            + "where c.status in :statuses and (lower(c.claimReference) like :q or lower(cu.name) like :q "
            + "or lower(coalesce(c.invoiceNumber, '')) like :q or cu.phoneE164 like :q)")
    Page<Claim> search(@Param("statuses") Collection<ClaimStatus> statuses, @Param("q") String q, Pageable pageable);
}
