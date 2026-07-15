package com.dsports.identity.infrastructure.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

import java.util.List;

public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationManager(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        return Mono.just(authentication)
                .map(auth -> {
                    var token = auth.getCredentials().toString();
                    var claims = tokenProvider.validate(token);
                    var userId = claims.getSubject();
                    @SuppressWarnings("unchecked")
                    var roles = claims.get("roles", List.class);
                    var authorities = ((List<String>) roles).stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
                    return new UsernamePasswordAuthenticationToken(userId, token, authorities);
                });
    }
}
