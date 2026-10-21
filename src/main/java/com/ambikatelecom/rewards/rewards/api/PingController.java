package com.ambikatelecom.rewards.rewards.api;

import com.ambikatelecom.rewards.rewards.persistence.RewardTierRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 1 connectivity check: proves frontend to backend to database. Safe to remove later. */
@RestController
@RequestMapping("/api/public")
public class PingController {

    private final RewardTierRepository tiers;

    public PingController(RewardTierRepository tiers) {
        this.tiers = tiers;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "service", "Ambika Telecom Rewards",
                "time", Instant.now().toString(),
                "rewardTiersConfigured", tiers.count());
    }
}
