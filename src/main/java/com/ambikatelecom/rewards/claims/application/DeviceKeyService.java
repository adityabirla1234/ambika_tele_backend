package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Normalizes IMEI/serial numbers and derives the keyed duplicate-matching key.
 *
 * HMAC key rotation (Phase 7): new keys are made with the current key and stored with its version. Keys listed in
 * app.security.device-hmac-previous-keys ({@code version:key}, comma separated) are only used to RECOGNISE rows that
 * have not been re-keyed yet, so duplicate checks and status lookups keep working while KeyRotationRunner moves
 * the rows over. See README, "Key rotation".
 */
@Service
public class DeviceKeyService {

    /** key/keyVersion are for the current key; allKeys has the matching key under every configured version. */
    public record NormalizedDevice(String normalized, String key, int keyVersion, List<String> allKeys) {}

    private final int keyVersion;
    /** version -> key bytes; the current version first. */
    private final Map<Integer, byte[]> hmacKeys = new LinkedHashMap<>();

    public DeviceKeyService(@Value("${app.security.device-hmac-key}") String hmacKey,
                            @Value("${app.security.device-hmac-key-version}") int keyVersion,
                            @Value("${app.security.device-hmac-previous-keys:}") String previousKeys) {
        if (hmacKey.length() < 32) throw new IllegalStateException("DEVICE_HMAC_KEY must be at least 32 characters.");
        this.keyVersion = keyVersion;
        hmacKeys.put(keyVersion, hmacKey.getBytes(StandardCharsets.UTF_8));
        for (String entry : previousKeys.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int colon = e.indexOf(':');
            if (colon < 1) throw new IllegalStateException("DEVICE_HMAC_PREVIOUS_KEYS entries look like 1:<key>.");
            int version;
            try {
                version = Integer.parseInt(e.substring(0, colon).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("DEVICE_HMAC_PREVIOUS_KEYS has a version that is not a number.");
            }
            String key = e.substring(colon + 1).trim();
            if (key.length() < 32) throw new IllegalStateException("Every HMAC key must be at least 32 characters.");
            if (hmacKeys.containsKey(version)) {
                throw new IllegalStateException("DEVICE_HMAC_PREVIOUS_KEYS repeats a version or reuses the current one.");
            }
            hmacKeys.put(version, key.getBytes(StandardCharsets.UTF_8));
        }
    }

    public int currentVersion() {
        return keyVersion;
    }

    public NormalizedDevice normalize(boolean imei, String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        String normalized;
        if (imei) {
            if (!cleaned.matches("\\d{15}") || !luhnValid(cleaned)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_IDENTIFIER",
                        "Enter the 15-digit IMEI 1. Dial *#06# on your phone to see it.", "deviceIdentifier");
            }
            normalized = cleaned;
        } else {
            normalized = cleaned.toUpperCase();
            if (!normalized.matches("[A-Z0-9/]{4,40}")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_IDENTIFIER",
                        "Enter the serial number exactly as printed on the product or bill.", "deviceIdentifier");
            }
        }
        return forNormalized(imei, normalized);
    }

    /** Keys for an identifier that is already normalized (used when re-keying stored rows). */
    public NormalizedDevice forNormalized(boolean imei, String normalized) {
        String typed = typed(imei, normalized);
        List<String> all = new ArrayList<>(hmacKeys.size());
        for (Map.Entry<Integer, byte[]> e : hmacKeys.entrySet()) all.add(hmacHex(e.getValue(), typed));
        return new NormalizedDevice(normalized, all.get(0), keyVersion, List.copyOf(all));
    }

    /**
     * Works out whether a stored identifier was an IMEI or a serial number by checking which one reproduces the stored
     * key under the version it was made with. Empty when neither does (wrong key, or the data was altered).
     */
    public Optional<Boolean> detectImei(int storedVersion, String normalized, String storedKey) {
        byte[] key = hmacKeys.get(storedVersion);
        if (key == null) return Optional.empty();
        if (hmacHex(key, typed(true, normalized)).equals(storedKey)) return Optional.of(true);
        if (hmacHex(key, typed(false, normalized)).equals(storedKey)) return Optional.of(false);
        return Optional.empty();
    }

    /**
     * Keys to try when a customer types an identifier on the status page, under every configured key version.
     * We do not know whether it is an IMEI or a serial number, so an identifier that could be either yields both.
     * Never throws: a malformed value simply matches nothing, which the caller reports as "device not registered".
     */
    public List<String> lookupKeys(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        List<String> keys = new ArrayList<>(4);
        for (byte[] key : hmacKeys.values()) {
            if (cleaned.matches("\\d{15}") && luhnValid(cleaned)) keys.add(hmacHex(key, typed(true, cleaned)));
            String serial = cleaned.toUpperCase();
            if (serial.matches("[A-Z0-9/]{4,40}")) keys.add(hmacHex(key, typed(false, serial)));
        }
        return keys;
    }

    private static String typed(boolean imei, String normalized) {
        return (imei ? "IMEI:" : "SN:") + normalized;
    }

    private static String hmacHex(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed");
        }
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
