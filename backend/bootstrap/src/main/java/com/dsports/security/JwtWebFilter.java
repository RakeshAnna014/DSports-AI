package com.dsports.security;

import com.dsports.identity.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtWebFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public JwtWebFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        var token = authHeader.substring(BEARER_PREFIX.length());

        try {
            var claims = tokenProvider.validate(token);
            var userId = claims.getSubject();
            var roles = claims.get("roles", List.class);
            var authorities = ((List<String>) roles).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();

            var authentication = new UsernamePasswordAuthenticationToken(userId, token, authorities);

            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return chain.filter(exchange);
        }
    }
}
