package com.dsports.identity.infrastructure.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class JwtWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtAuthenticationManager authenticationManager;

    public JwtWebFilter(JwtAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        var token = authHeader.substring(BEARER_PREFIX.length());
        var auth = new JwtAuthenticationToken(token);

        return authenticationManager.authenticate(auth)
                .flatMap(authentication -> {
                    var mutatedExchange = exchange.mutate()
                            .request(b -> b.header("X-User-Id", authentication.getPrincipal().toString()))
                            .build();
                    return chain.filter(mutatedExchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                })
                .onErrorResume(e -> chain.filter(exchange));
    }
}
