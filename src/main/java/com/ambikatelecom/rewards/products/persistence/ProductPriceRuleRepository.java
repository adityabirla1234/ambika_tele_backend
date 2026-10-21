package com.ambikatelecom.rewards.products.persistence;

import com.ambikatelecom.rewards.products.domain.ProductPriceRule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductPriceRuleRepository extends JpaRepository<ProductPriceRule, Long> {

    @Query("select r from ProductPriceRule r where r.product.id = :productId and r.effectiveFrom <= :today "
            + "and (r.effectiveTo is null or r.effectiveTo >= :today) order by r.effectiveFrom desc")
    List<ProductPriceRule> findCurrent(@Param("productId") Long productId, @Param("today") LocalDate today,
                                       Pageable pageable);

    @Query("select r from ProductPriceRule r where r.product.id in :ids and r.effectiveFrom <= :today "
            + "and (r.effectiveTo is null or r.effectiveTo >= :today) order by r.effectiveFrom desc, r.id desc")
    List<ProductPriceRule> findCurrentForProducts(@Param("ids") java.util.Collection<Long> ids, @Param("today") LocalDate today);

    List<ProductPriceRule> findByProductIdOrderByEffectiveFromDescIdDesc(Long productId);
}
