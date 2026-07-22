package com.dsports.identity.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

import java.util.List;

public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationManager.class);
    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationManager(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Authentication> authenticate(Authentication authentication) {
        return Mono.just(authentication)
                .flatMap(auth -> {
                    try {
                        var token = auth.getCredentials().toString();
                        var claims = tokenProvider.validate(token);
                        var userId = claims.getSubject();
                        var roles = claims.get("roles", List.class);
                        var authorities = ((List<String>) roles).stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .toList();
                        return Mono.just(new UsernamePasswordAuthenticationToken(userId, token, authorities));
                    } catch (Exception e) {
                        log.debug("JWT authentication failed: {}", e.getMessage());
                        return Mono.error(new BadCredentialsException("Invalid or expired token", e));
                    }
                });
    }
}
