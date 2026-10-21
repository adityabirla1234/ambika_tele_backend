package com.ambikatelecom.rewards.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Roles come from OUR database, never from the token or from browser headers.
 * A valid Cloudflare login for an unknown or inactive email gets no authorities.
 */
@Service
public class StaffAccessService {

    private final StaffUserRepository users;

    public StaffAccessService(StaffUserRepository users) {
        this.users = users;
    }

    public Collection<GrantedAuthority> authoritiesFor(String email) {
        if (email == null || email.isBlank()) return List.of();
        return users.findByEmail(email.toLowerCase())
                .filter(StaffUser::isActive)
                .map(u -> u.getRoles().stream()
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.name()))
                        .toList())
                .orElse(List.of());
    }
}
