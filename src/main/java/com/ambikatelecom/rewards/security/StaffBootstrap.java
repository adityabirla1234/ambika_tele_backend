package com.ambikatelecom.rewards.security;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first staff accounts from environment settings.
 *
 * - An account is only created when BOTH its email and password are set. There is no default password anywhere.
 * - A password is only ever set on an account that has none. An account that already has a password is never
 *   touched, so leaving these settings in place cannot reset or overwrite anything.
 * - A bootstrap password must be changed at the first sign-in. Remove the password setting after that.
 * - The password is never logged.
 */
@Component
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private final StaffUserRepository users;
    private final StaffAuthService auth;
    private final String adminEmail;
    private final String adminPassword;
    private final String managerEmail;
    private final String managerPassword;

    public StaffBootstrap(StaffUserRepository users, StaffAuthService auth,
                          @Value("${app.bootstrap.claim-admin-email:}") String adminEmail,
                          @Value("${app.bootstrap.claim-admin-password:}") String adminPassword,
                          @Value("${app.bootstrap.product-manager-email:}") String managerEmail,
                          @Value("${app.bootstrap.product-manager-password:}") String managerPassword) {
        this.users = users;
        this.auth = auth;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.managerEmail = managerEmail;
        this.managerPassword = managerPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensure(adminEmail, adminPassword, StaffRole.CLAIM_ADMIN, "BOOTSTRAP_CLAIM_ADMIN");
        ensure(managerEmail, managerPassword, StaffRole.PRODUCT_MANAGER, "BOOTSTRAP_PRODUCT_MANAGER");
    }

    private void ensure(String rawEmail, String password, StaffRole role, String settingPrefix) {
        if (rawEmail == null || rawEmail.isBlank()) return;
        String email = StaffAuthService.normalizeEmail(rawEmail);
        if (email == null) {
            throw new IllegalStateException(settingPrefix + "_EMAIL is not a valid email address of at most "
                    + StaffAuthService.MAX_EMAIL_LENGTH + " characters.");
        }
        StaffUser user = users.findByEmail(email).orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        boolean havePasswordSetting = password != null && !password.isBlank();

        if (!hasPassword && !havePasswordSetting) {
            log.warn("The {} account has no password and {}_PASSWORD is not set, so nobody can sign in to it yet.",
                    role, settingPrefix);
            if (user == null) return; // do not create an account nobody can use
        }
        if (user == null) user = new StaffUser(email);

        boolean changed = user.getRoles().add(role);
        if (!hasPassword && havePasswordSetting) {
            Optional<String> problem = PasswordPolicy.check(password, email);
            if (problem.isPresent()) {
                throw new IllegalStateException(settingPrefix + "_PASSWORD is not acceptable: " + problem.get());
            }
            user.setInitialPassword(auth.hashPassword(password), Instant.now());
            changed = true;
            log.info("Set an initial password for the {} account. It must be changed at first sign-in.", role);
        }
        if (changed) users.save(user);
    }
}
