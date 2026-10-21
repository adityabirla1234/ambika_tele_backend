package com.ambikatelecom.rewards.privacy;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.claims.application.DeviceKeyService;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.common.EncryptionService;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves stored claims onto the CURRENT encryption key and HMAC key. Does nothing unless ROTATE_KEYS=true
 * (app.maintenance.rotate-keys), so a normal start never touches data. See README, "Key rotation".
 *
 * Per claim, in its own transaction with the claim row locked: decrypt the device number with whichever key version wrote it,
 * work out whether it was an IMEI or a serial number by checking which one reproduces the stored key (this also
 * proves the data is intact), then store the new key, the new key version and a fresh encryption. Safe to run again
 * and safe if it stops half way: rows already moved are skipped, and duplicate checks accept every configured version meanwhile.
 *
 * Claims whose personal data was erased cannot be re-keyed (the number is gone). If their device key was kept, they stay on
 * their old HMAC version, and that old key must stay in DEVICE_HMAC_PREVIOUS_KEYS. The final log line says whether any remain.
 */
@Component
@Order(100)
public class KeyRotationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationRunner.class);
    private static final int BATCH = 100;

    private final boolean enabled;
    private final ClaimRepository claims;
    private final DeviceKeyService devices;
    private final EncryptionService encryption;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public KeyRotationRunner(@Value("${app.maintenance.rotate-keys:false}") boolean enabled, ClaimRepository claims,
                             DeviceKeyService devices, EncryptionService encryption, AuditService audit,
                             PlatformTransactionManager tm) {
        this.enabled = enabled;
        this.claims = claims;
        this.devices = devices;
        this.encryption = encryption;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        String encPrefix = "v" + encryption.currentVersion() + ":%";
        int hmacVersion = devices.currentVersion();
        int moved = 0, failed = 0;
        long lastId = 0;
        log.info("KEY_ROTATION_START hmacVersion={} encryptionVersion={}", hmacVersion, encryption.currentVersion());
        while (true) {
            List<Long> ids = claims.findRotationCandidateIds(lastId, hmacVersion, encPrefix, PageRequest.of(0, BATCH));
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                lastId = id;
                try {
                    Boolean done = tx.execute(status -> rekeyOne(id));
                    if (Boolean.TRUE.equals(done)) moved++; else failed++;
                } catch (RuntimeException e) {
                    failed++; // type only: never the value
                    log.error("KEY_ROTATION_CLAIM_FAILED claimId={} type={}", id, e.getClass().getName());
                }
            }
        }
        Map<Integer, Long> byVersion = new TreeMap<>();
        for (Object[] row : claims.countByKeyVersion()) byVersion.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        long onOldVersions = byVersion.entrySet().stream().filter(e -> e.getKey() != hmacVersion).mapToLong(Map.Entry::getValue).sum();
        log.info("KEY_ROTATION_DONE moved={} failed={} claimsPerHmacVersion={} claimsOnOldHmacVersions={}",
                moved, failed, byVersion, onOldVersions);
        if (failed > 0) log.warn("KEY_ROTATION_INCOMPLETE {} claim(s) could not be moved. Keep the previous keys configured.", failed);
        if (onOldVersions > 0) {
            log.warn("KEY_ROTATION_OLD_HMAC_KEYS_STILL_NEEDED {} claim(s) are still on an old HMAC version (failed or erased). Do not remove that key.", onOldVersions);
        }
        audit.record("KEY_ROTATION", "SYSTEM", null, "system",
                "{\"moved\":" + moved + ",\"failed\":" + failed + ",\"onOldHmacVersions\":" + onOldVersions + "}");
    }

    /** Returns true when the claim was moved (or needed nothing), false when it could not be verified. */
    private boolean rekeyOne(Long id) {
        Optional<Claim> found = claims.findByIdForUpdate(id);
        if (found.isEmpty() || found.get().isPersonalDataErased()) return true;
        Claim c = found.get();
        if (c.getDeviceKey().startsWith(Claim.RELEASED_PREFIX)) {
            // Released for a second-hand sale: its key is random and must stay that way. Only move the encryption.
            c.rekey(c.getDeviceKey(), devices.currentVersion(), encryption.encrypt(encryption.decrypt(c.getDeviceIdentifierEnc())));
            claims.saveAndFlush(c);
            return true;
        }
        String plain = encryption.decrypt(c.getDeviceIdentifierEnc());
        Optional<Boolean> imei = devices.detectImei(c.getDeviceKeyVersion(), plain, c.getDeviceKey());
        if (imei.isEmpty()) return false; // key does not reproduce: wrong key configured or data changed. Leave it alone.
        DeviceKeyService.NormalizedDevice d = devices.forNormalized(imei.get(), plain);
        c.rekey(d.key(), d.keyVersion(), encryption.encrypt(plain));
        claims.saveAndFlush(c);
        return true;
    }
}
