package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.claims.domain.Claim;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Not transactional itself. If two requests race and one loses on a unique constraint, the whole
 * transaction rolls back and the retry sees the winner's rows, giving a clean duplicate or replay result.
 */
@Service
public class ClaimService {

    private final ClaimTransaction tx;

    public ClaimService(ClaimTransaction tx) {
        this.tx = tx;
    }

    public Claim submit(SubmitClaimCommand cmd, String idempotencyKey) {
        try {
            return tx.submit(cmd, idempotencyKey);
        } catch (DataIntegrityViolationException race) {
            return tx.submit(cmd, idempotencyKey);
        }
    }
}
