package com.ambikatelecom.rewards.rewards.persistence;

import com.ambikatelecom.rewards.rewards.domain.Reward;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardRepository extends JpaRepository<Reward, Long> {
    Optional<Reward> findByClaimId(Long claimId);
}
