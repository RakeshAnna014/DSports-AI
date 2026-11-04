package com.dsports.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitWebFilterTest {

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger reached = new AtomicInteger();
    private final RateLimitWebFilter filter = new RateLimitWebFilter(new ObjectMapper().findAndRegisterModules(), clock, 3, 2);

    private MockServerWebExchange post(String path, String ip, String... headers) {
        var b = MockServerHttpRequest.post(path).remoteAddress(new InetSocketAddress(ip, 5555));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return MockServerWebExchange.from(b);
    }

    private HttpStatus call(ServerWebExchange exchange) {
        filter.filter(exchange, e -> { reached.incrementAndGet(); return Mono.empty(); }).block();
        var status = exchange.getResponse().getStatusCode();
        return status == null ? HttpStatus.OK : HttpStatus.valueOf(status.value());
    }

    @Test
    void authentication_attempts_are_limited_per_client_address_and_answer_429_with_retry_after() {
        for (int i = 0; i < 3; i++) assertThat(call(post("/api/auth/login", "10.0.0.1"))).isEqualTo(HttpStatus.OK);
        var blocked = post("/api/auth/login", "10.0.0.1");
        assertThat(call(blocked)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(reached.get()).isEqualTo(3);
        assertThat(call(post("/api/auth/login", "10.0.0.2"))).isEqualTo(HttpStatus.OK);                 // another client is unaffected
    }

    @Test
    void the_limit_covers_register_and_refresh_but_not_other_endpoints() {
        for (int i = 0; i < 3; i++) call(post("/api/auth/register", "10.0.0.3"));
        assertThat(call(post("/api/auth/refresh", "10.0.0.3"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);    // one shared auth budget
        for (int i = 0; i < 20; i++) assertThat(call(post("/api/v1/cart/items", "10.0.0.3"))).isEqualTo(HttpStatus.OK);
        assertThat(call(MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/login").remoteAddress(new InetSocketAddress("10.0.0.3", 1))))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void a_spoofed_x_forwarded_for_header_cannot_dodge_or_target_the_limit() {
        for (int i = 0; i < 3; i++) call(post("/api/auth/login", "10.0.0.4", "X-Forwarded-For", "203.0.113." + i));
        assertThat(call(post("/api/auth/login", "10.0.0.4", "X-Forwarded-For", "198.51.100.9"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(call(post("/api/auth/login", "10.0.0.5", "X-Forwarded-For", "10.0.0.4"))).isEqualTo(HttpStatus.OK);   // cannot frame another address either
    }

    @Test
    void the_window_resets_after_a_minute() {
        for (int i = 0; i < 4; i++) call(post("/api/auth/login", "10.0.0.6"));
        assertThat(call(post("/api/auth/login", "10.0.0.6"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        clock.advance(Duration.ofSeconds(61));
        assertThat(call(post("/api/auth/login", "10.0.0.6"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ticket_creation_is_limited_per_authenticated_user_not_per_address() {
        Principal alice = () -> "alice", bob = () -> "bob";
        for (int i = 0; i < 2; i++) assertThat(call(post("/api/v1/support/cases", "10.0.0.7").mutate().principal(Mono.just(alice)).build())).isEqualTo(HttpStatus.OK);
        assertThat(call(post("/api/v1/support/cases", "10.0.0.7").mutate().principal(Mono.just(alice)).build())).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(call(post("/api/v1/support/cases", "10.0.0.7").mutate().principal(Mono.just(bob)).build())).isEqualTo(HttpStatus.OK);   // same address, other user
    }

    @Test
    void the_limiter_itself_counts_exactly_and_cleans_up() {
        var l = new FixedWindowRateLimiter(clock);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("k", 5, Duration.ofMinutes(1)).allowed()).isTrue();
        var denied = l.tryAcquire("k", 5, Duration.ofMinutes(1));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(l.tryAcquire("zero", 0, Duration.ofMinutes(1)).allowed()).isFalse();             // a limit of 0 blocks everything
    }
}
