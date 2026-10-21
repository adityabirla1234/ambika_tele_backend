package com.ambikatelecom.rewards.claims.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stateless, signed, expiring token that proves "this browser looked up (or just submitted) claim N".
 * It lets the status page poll and scratch without the device identifier ever appearing in a URL, and
 * without an access-token table. The signing key is derived from DEVICE_HMAC_KEY with a fixed label, so
 * it needs no extra secret. Format: claimId.expiryEpochSeconds.signature
 */
@Service
public class StatusTokenService {

    private final byte[] key;
    private final Duration ttl;

    public StatusTokenService(@Value("${app.security.device-hmac-key}") String baseKey,
                              @Value("${app.security.status-token-ttl-hours:24}") long ttlHours) {
        this.key = hmac(baseKey.getBytes(StandardCharsets.UTF_8), "status-token-v1".getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(long claimId) {
        String payload = claimId + "." + Instant.now().plus(ttl).getEpochSecond();
        return payload + "." + sign(payload);
    }

    /** Returns the claim id the token was issued for, or null if it is malformed, forged or expired. */
    public Long verify(String token) {
        if (token == null || token.length() > 120) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            String payload = parts[0] + "." + parts[1];
            byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.UTF_8))) return null;
            if (Long.parseLong(parts[1]) < Instant.now().getEpochSecond()) return null;
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sign(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
