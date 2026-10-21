package com.ambikatelecom.rewards.rewards.persistence;

import com.ambikatelecom.rewards.rewards.domain.RewardTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardTierRepository extends JpaRepository<RewardTier, Long> {

    @Query("select t from RewardTier t where t.active = true and t.purchaseMin <= :price and t.purchaseMax >= :price "
            + "and t.effectiveFrom <= :today and (t.effectiveTo is null or t.effectiveTo >= :today) "
            + "order by t.purchaseMin desc")
    List<RewardTier> findActiveForPrice(@Param("price") BigDecimal price, @Param("today") LocalDate today);

    @Query("select t from RewardTier t where t.active = true and t.effectiveFrom <= :today "
            + "and (t.effectiveTo is null or t.effectiveTo >= :today)")
    List<RewardTier> findAllActive(@Param("today") LocalDate today);

    List<RewardTier> findAllByOrderByPurchaseMinAsc();

    /** Active tiers whose inclusive purchase range touches [min, max], other than the tier being edited. */
    @Query("select t from RewardTier t where t.active = true and t.id <> :excludeId "
            + "and t.purchaseMin <= :max and t.purchaseMax >= :min order by t.purchaseMin")
    List<RewardTier> findOverlapping(@Param("min") BigDecimal min, @Param("max") BigDecimal max,
                                     @Param("excludeId") Long excludeId);
}
