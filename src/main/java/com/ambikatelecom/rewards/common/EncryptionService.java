package com.ambikatelecom.rewards.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM for fields that must be readable by staff later (the raw IMEI or serial).
 *
 * Key rotation (Phase 7): every new value is stored as {@code v<version>:<base64(iv + ciphertext)>}. Values written
 * before Phase 7 have no prefix and are treated as version 1. The current key encrypts; the current key and any keys
 * listed in app.security.device-encryption-previous-keys (format {@code version:base64key}, comma separated) can decrypt.
 * See README, "Key rotation", for the runbook.
 */
@Service
public class EncryptionService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int LEGACY_VERSION = 1;
    private static final Pattern PREFIXED = Pattern.compile("^v(\\d{1,4}):(.+)$", Pattern.DOTALL);

    private final int currentVersion;
    private final Map<Integer, SecretKeySpec> keys = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${app.security.device-encryption-key}") String base64Key,
                             @Value("${app.security.device-encryption-key-version:1}") int currentVersion,
                             @Value("${app.security.device-encryption-previous-keys:}") String previousKeys) {
        if (currentVersion < 1) throw new IllegalStateException("device-encryption-key-version must be 1 or higher.");
        this.currentVersion = currentVersion;
        keys.put(currentVersion, toKey(base64Key));
        for (String entry : previousKeys.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int colon = e.indexOf(':');
            if (colon < 1) throw new IllegalStateException("DEVICE_ENCRYPTION_PREVIOUS_KEYS entries look like 1:<base64key>.");
            int version;
            try {
                version = Integer.parseInt(e.substring(0, colon).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("DEVICE_ENCRYPTION_PREVIOUS_KEYS has a version that is not a number.");
            }
            if (keys.containsKey(version)) {
                throw new IllegalStateException("DEVICE_ENCRYPTION_PREVIOUS_KEYS repeats a version or reuses the current one.");
            }
            keys.put(version, toKey(e.substring(colon + 1).trim()));
        }
    }

    private static SecretKeySpec toKey(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("An encryption key is not valid base64.");
        }
        if (raw.length != 32) throw new IllegalStateException("Encryption keys must be 32 bytes (base64).");
        return new SecretKeySpec(raw, "AES");
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** True when the stored value is already encrypted with the current key version. */
    public boolean isCurrent(String encoded) {
        Matcher m = encoded == null ? null : PREFIXED.matcher(encoded);
        return m != null && m.matches() && Integer.parseInt(m.group(1)) == currentVersion;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            String body = Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
            return "v" + currentVersion + ":" + body;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed");
        }
    }

    public String decrypt(String encoded) {
        int version = LEGACY_VERSION;
        String payload = encoded;
        Matcher m = PREFIXED.matcher(encoded);
        if (m.matches()) {
            version = Integer.parseInt(m.group(1));
            payload = m.group(2);
        }
        SecretKeySpec key = keys.get(version);
        if (key == null) throw new IllegalStateException("No encryption key is configured for version " + version + ".");
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(payload));
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Deliberately no cause: it is a wrong key or damaged data, and nothing from the value should be logged.
            throw new IllegalStateException("Decryption failed");
        }
    }
}
