package com.ambikatelecom.rewards.rewards.application;

import com.ambikatelecom.rewards.products.application.ProductAuditService;
import com.ambikatelecom.rewards.rewards.domain.RewardTier;
import com.ambikatelecom.rewards.rewards.persistence.RewardTierRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reward tier management. Active tiers may not overlap (boundaries are inclusive). Edits affect new claims
 * only: each claim snapshotted its tier range when it was submitted. Tiers are deactivated, never deleted.
 */
@Service
public class TierManagementService {

    private static final BigDecimal MAX = BigDecimal.valueOf(10_000_000);

    public record Input(BigDecimal purchaseMin, BigDecimal purchaseMax, BigDecimal rewardMin, BigDecimal rewardMax) {}

    public record TierView(Long id, BigDecimal purchaseMin, BigDecimal purchaseMax, BigDecimal rewardMin,
                           BigDecimal rewardMax, boolean active, LocalDate effectiveFrom) {}

    private final RewardTierRepository tiers;
    private final ProductAuditService audit;
    private final ZoneId shopZone;

    public TierManagementService(RewardTierRepository tiers, ProductAuditService audit,
                                 @Value("${app.shop.timezone}") String shopTimezone) {
        this.tiers = tiers;
        this.audit = audit;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    @Transactional(readOnly = true)
    public List<TierView> list() {
        return tiers.findAllByOrderByPurchaseMinAsc().stream().map(TierManagementService::view).toList();
    }

    @Transactional
    public TierView create(Input in, String actor) {
        Input v = validate(in);
        requireNoOverlap(v, 0L);
        RewardTier t = tiers.save(new RewardTier(v.purchaseMin(), v.purchaseMax(), v.rewardMin(), v.rewardMax(),
                LocalDate.now(shopZone)));
        audit.record("REWARD_TIER", t.getId(), null, "TIER_CREATED", actor, values(v));
        return view(t);
    }

    @Transactional
    public TierView update(Long id, Input in, String actor) {
        RewardTier t = load(id);
        Input v = validate(in);
        if (t.isActive()) requireNoOverlap(v, id);
        Map<String, Object> changes = new LinkedHashMap<>();
        diff("purchaseMin", t.getPurchaseMin(), v.purchaseMin(), changes);
        diff("purchaseMax", t.getPurchaseMax(), v.purchaseMax(), changes);
        diff("rewardMin", t.getRewardMin(), v.rewardMin(), changes);
        diff("rewardMax", t.getRewardMax(), v.rewardMax(), changes);
        if (!changes.isEmpty()) {
            t.update(v.purchaseMin(), v.purchaseMax(), v.rewardMin(), v.rewardMax());
            audit.record("REWARD_TIER", id, null, "TIER_UPDATED", actor, changes);
        }
        return view(t);
    }

    @Transactional
    public TierView setActive(Long id, boolean active, String actor) {
        RewardTier t = load(id);
        if (t.isActive() != active) {
            if (active) {
                requireNoOverlap(new Input(t.getPurchaseMin(), t.getPurchaseMax(), t.getRewardMin(), t.getRewardMax()), id);
            }
            t.setActive(active);
            audit.record("REWARD_TIER", id, null, active ? "TIER_ACTIVATED" : "TIER_DEACTIVATED", actor,
                    Map.of("active", active));
        }
        return view(t);
    }

    private void requireNoOverlap(Input v, Long excludeId) {
        List<RewardTier> clash = tiers.findOverlapping(v.purchaseMin(), v.purchaseMax(), excludeId);
        if (!clash.isEmpty()) {
            RewardTier c = clash.get(0);
            NumberFormat inr = NumberFormat.getIntegerInstance(new Locale("en", "IN"));
            throw new ApiException(HttpStatus.CONFLICT, "TIER_OVERLAP",
                    "These purchase prices overlap an active tier (\u20b9" + inr.format(c.getPurchaseMin()) + " to \u20b9"
                            + inr.format(c.getPurchaseMax()) + "). Change one of the ranges first.", "purchaseMin");
        }
    }

    private static Input validate(Input in) {
        BigDecimal pMin = whole(in.purchaseMin(), "purchaseMin");
        BigDecimal pMax = whole(in.purchaseMax(), "purchaseMax");
        BigDecimal rMin = whole(in.rewardMin(), "rewardMin");
        BigDecimal rMax = whole(in.rewardMax(), "rewardMax");
        if (pMin.compareTo(pMax) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "The lowest purchase price cannot be higher than the highest.", "purchaseMin");
        }
        if (rMin.compareTo(rMax) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "The lowest reward cannot be higher than the highest.", "rewardMin");
        }
        return new Input(pMin, pMax, rMin, rMax);
    }

    private static BigDecimal whole(BigDecimal v, String field) {
        if (v == null || v.signum() <= 0 || v.stripTrailingZeros().scale() > 0 || v.compareTo(MAX) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Enter a whole number of rupees, more than zero.", field);
        }
        return v.setScale(2);
    }

    private RewardTier load(Long id) {
        return tiers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "TIER_NOT_FOUND", "Reward tier not found."));
    }

    private static void diff(String field, BigDecimal before, BigDecimal after, Map<String, Object> out) {
        if (before.compareTo(after) != 0) {
            out.put(field, Map.of("from", before.toPlainString(), "to", after.toPlainString()));
        }
    }

    private static Map<String, Object> values(Input v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purchaseMin", v.purchaseMin().toPlainString());
        m.put("purchaseMax", v.purchaseMax().toPlainString());
        m.put("rewardMin", v.rewardMin().toPlainString());
        m.put("rewardMax", v.rewardMax().toPlainString());
        return m;
    }

    private static TierView view(RewardTier t) {
        return new TierView(t.getId(), t.getPurchaseMin(), t.getPurchaseMax(), t.getRewardMin(), t.getRewardMax(),
                t.isActive(), t.getEffectiveFrom());
    }
}
