package com.ambikatelecom.rewards.claims.persistence;

import com.ambikatelecom.rewards.claims.domain.ClaimReview;
import com.ambikatelecom.rewards.claims.domain.ReviewDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimReviewRepository extends JpaRepository<ClaimReview, Long> {
    Optional<ClaimReview> findFirstByClaimIdAndDecisionOrderByCreatedAtDescIdDesc(Long claimId, ReviewDecision decision);
    List<ClaimReview> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);

    /** Erasure only: review reasons are free text and can contain personal data. Rows stay; the text goes. */
    @Modifying
    @Query(value = "update claim_reviews set reason = '[removed]' where claim_id = :claimId and reason is not null", nativeQuery = true)
    int scrubReasons(@Param("claimId") Long claimId);
}
