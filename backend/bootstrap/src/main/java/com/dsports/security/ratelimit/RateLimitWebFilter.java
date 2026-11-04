package com.dsports.security.ratelimit;

import com.dsports.exception.ApiErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Targeted abuse limits on the endpoints that are cheap to hammer and expensive or sensitive to serve:
 * authentication attempts (per client address) and support-ticket creation/messages (per authenticated user).
 * <p>
 * The client address is the TCP peer address. {@code X-Forwarded-For} is deliberately NOT trusted (any client can set it); behind a reverse proxy
 * every user would share the proxy's address, so deployments behind one must enforce the auth limit at the proxy/gateway instead.
 * It runs after Spring Security (order -50) so the authenticated principal is available.
 */
@Component
@Order(-50)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);
    private static final PathPatternParser PARSER = new PathPatternParser();

    private enum Scope { CLIENT, USER }

    private record Rule(String name, HttpMethod method, PathPattern path, Scope scope, int limit) {}

    private final FixedWindowRateLimiter limiter;
    private final ObjectMapper mapper;
    private final Duration window = Duration.ofMinutes(1);
    private final List<Rule> rules;

    public RateLimitWebFilter(ObjectMapper mapper, Clock clock,
                              @Value("${app.rate-limit.auth-per-minute:20}") int authPerMinute,
                              @Value("${app.rate-limit.tickets-per-minute:10}") int ticketsPerMinute) {
        this.limiter = new FixedWindowRateLimiter(clock);
        this.mapper = mapper;
        this.rules = List.of(
            new Rule("auth", HttpMethod.POST, PARSER.parse("/api/auth/{action:login|register|refresh}"), Scope.CLIENT, authPerMinute),
            new Rule("ticket", HttpMethod.POST, PARSER.parse("/api/v1/support/cases"), Scope.USER, ticketsPerMinute),
            new Rule("ticket-message", HttpMethod.POST, PARSER.parse("/api/v1/support/cases/*/{action:messages|escalate|reopen}"), Scope.USER, ticketsPerMinute * 3));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        for (var rule : rules) {
            if (request.getMethod() != rule.method() || !rule.path().matches(request.getPath().pathWithinApplication())) {
                continue;
            }
            return key(exchange, rule).flatMap(key -> {
                var decision = limiter.tryAcquire(rule.name() + ":" + key, rule.limit(), window);
                if (decision.allowed()) {
                    return chain.filter(exchange);
                }
                log.warn("Rate limit '{}' exceeded on {} (retry after {}s)", rule.name(), request.getPath().value(), decision.retryAfterSeconds());
                return reject(exchange, decision.retryAfterSeconds());
            });
        }
        return chain.filter(exchange);
    }

    private Mono<String> key(ServerWebExchange exchange, Rule rule) {
        var address = exchange.getRequest().getRemoteAddress();
        var client = "ip:" + (address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress());
        if (rule.scope() == Scope.CLIENT) {
            return Mono.just(client);
        }
        return exchange.getPrincipal().map(p -> "user:" + p.getName()).defaultIfEmpty(client);
    }

    private Mono<Void> reject(ServerWebExchange exchange, long retryAfterSeconds) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            var body = mapper.writeValueAsBytes(ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests. Please wait " + retryAfterSeconds + " seconds and try again.", exchange));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (Exception e) {
            return response.setComplete();
        }
    }
}
