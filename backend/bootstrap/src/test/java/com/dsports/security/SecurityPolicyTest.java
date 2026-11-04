package com.dsports.security;

import com.dsports.identity.infrastructure.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * The real {@link SecurityConfig} and {@link JwtWebFilter} in front of probe endpoints, so the access policy itself is tested (not only the annotations on controllers):
 * who may reach which path, and that a token which is validly signed but not ours, expired, unsigned, tampered or malformed is never accepted.
 */
class SecurityPolicyTest {

    private static final String SECRET = "security-policy-test-secret-security-policy-test-secret-123";
    private static AnnotationConfigApplicationContext context;
    private static WebTestClient client;

    @Configuration
    @EnableWebFlux
    @Import({SecurityConfig.class, JwtWebFilter.class, Probe.class})
    static class Config {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
        }

        @Bean
        ObjectMapper objectMapper() {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        }
    }

    @RestController
    static class Probe {
        @GetMapping({"/api/auth/login", "/actuator/health", "/actuator/health/liveness", "/actuator/metrics", "/actuator/info", "/actuator/env", "/api/catalog/products",
                "/api/inventory/x", "/api/prices/x", "/api/v1/orders", "/api/admin/anything", "/api/v1/admin/sales/lines", "/api/v1/support/staff/cases", "/api/v1/cart", "/api-docs/openapi",
                "/some/unknown/route"})
        String ok() {
            return "ok";
        }
    }

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(Config.class);
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static String token(String subject, List<String> roles, String issuer, String audience, Duration life, String secret) {
        var now = Instant.now();
        var builder = Jwts.builder().subject(subject).claim("roles", roles).issuedAt(Date.from(now.minusSeconds(5))).expiration(Date.from(now.plus(life)));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience().add(audience).and();
        }
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String valid(String... roles) {
        return token("11111111-1111-1111-1111-111111111111", List.of(roles), JwtTokenProvider.DEFAULT_ISSUER, JwtTokenProvider.DEFAULT_AUDIENCE, Duration.ofMinutes(5), SECRET);
    }

    private int status(String path, String bearer) {
        var spec = client.get().uri(path);
        var response = bearer == null ? spec.exchange() : spec.header("Authorization", "Bearer " + bearer).exchange();
        return response.returnResult(String.class).getStatus().value();
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @CsvSource({
            // public by design
            "/api/auth/login,anonymous,200", "/actuator/health,anonymous,200", "/actuator/health/liveness,anonymous,200", "/api/catalog/products,anonymous,200", "/api/prices/x,anonymous,200", "/api/inventory/x,anonymous,200",
            // everything else needs a signed-in user
            "/api/v1/orders,anonymous,401", "/api/v1/cart,anonymous,401", "/some/unknown/route,anonymous,401", "/actuator/metrics,anonymous,401",
            "/api/v1/orders,customer,200", "/api/v1/cart,customer,200",
            // staff and admin areas
            "/api/admin/anything,anonymous,401", "/api/admin/anything,customer,403", "/api/admin/anything,support,403", "/api/admin/anything,admin,200",
            "/api/v1/admin/sales/lines,anonymous,401", "/api/v1/admin/sales/lines,customer,403", "/api/v1/admin/sales/lines,planner,403", "/api/v1/admin/sales/lines,admin,200",
            "/api/v1/support/staff/cases,customer,403", "/api/v1/support/staff/cases,support,200", "/api/v1/support/staff/cases,admin,200",
            // operational endpoints other than the health probes are for administrators only
            "/actuator/metrics,customer,403", "/actuator/info,customer,403", "/actuator/env,customer,403", "/actuator/metrics,support,403", "/actuator/metrics,admin,200", "/actuator/info,admin,200",
    })
    void accessPolicy(String path, String who, int expected) {
        String bearer = switch (who) {
            case "anonymous" -> null;
            case "customer" -> valid("CUSTOMER");
            case "support" -> valid("CUSTOMER", "SUPPORT_EXECUTIVE");
            case "planner" -> valid("CUSTOMER", "SALES_PLANNER");
            case "admin" -> valid("CUSTOMER", "ADMIN");
            default -> throw new IllegalArgumentException(who);
        };
        org.assertj.core.api.Assertions.assertThat(status(path, bearer)).isEqualTo(expected);
    }

    @Test
    void aValidlySignedTokenForAnotherAudienceOrIssuerIsRefused() {
        var id = "11111111-1111-1111-1111-111111111111";
        org.assertj.core.api.Assertions.assertThat(status("/api/v1/orders", token(id, List.of("ADMIN"), JwtTokenProvider.DEFAULT_ISSUER, "another-service", Duration.ofMinutes(5), SECRET))).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThat(status("/api/v1/orders", token(id, List.of("ADMIN"), "someone-else", JwtTokenProvider.DEFAULT_AUDIENCE, Duration.ofMinutes(5), SECRET))).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThat(status("/api/v1/orders", token(id, List.of("ADMIN"), null, null, Duration.ofMinutes(5), SECRET))).isEqualTo(401);
    }

    @Test
    void expiredWrongSecretUnsignedTamperedAndMalformedTokensAreRefused() {
        var id = "11111111-1111-1111-1111-111111111111";
        var path = "/api/admin/anything";
        org.assertj.core.api.Assertions.assertThat(status(path, token(id, List.of("ADMIN"), JwtTokenProvider.DEFAULT_ISSUER, JwtTokenProvider.DEFAULT_AUDIENCE, Duration.ofSeconds(-120), SECRET))).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThat(status(path, token(id, List.of("ADMIN"), JwtTokenProvider.DEFAULT_ISSUER, JwtTokenProvider.DEFAULT_AUDIENCE, Duration.ofMinutes(5),
                "a-completely-different-secret-a-completely-different-secret-9999"))).isEqualTo(401);
        var unsigned = Jwts.builder().subject(id).claim("roles", List.of("ADMIN")).issuer(JwtTokenProvider.DEFAULT_ISSUER).audience().add(JwtTokenProvider.DEFAULT_AUDIENCE).and()
                .expiration(Date.from(Instant.now().plusSeconds(300))).compact();
        org.assertj.core.api.Assertions.assertThat(status(path, unsigned)).isEqualTo(401);
        var good = valid("CUSTOMER");
        var tampered = good.substring(0, good.length() - 4) + "AAAA";
        org.assertj.core.api.Assertions.assertThat(status(path, tampered)).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThat(status(path, "not.a.jwt")).isEqualTo(401);
        org.assertj.core.api.Assertions.assertThat(status(path, "")).isEqualTo(401);
    }

    @Test
    void aRolesClaimInTheWrongShapeNeverGrantsAnything() {
        var now = Instant.now();
        var asString = Jwts.builder().subject("11111111-1111-1111-1111-111111111111").claim("roles", "ADMIN").issuer(JwtTokenProvider.DEFAULT_ISSUER).audience().add(JwtTokenProvider.DEFAULT_AUDIENCE).and()
                .expiration(Date.from(now.plusSeconds(300))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var missing = Jwts.builder().subject("11111111-1111-1111-1111-111111111111").issuer(JwtTokenProvider.DEFAULT_ISSUER).audience().add(JwtTokenProvider.DEFAULT_AUDIENCE).and()
                .expiration(Date.from(now.plusSeconds(300))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        org.assertj.core.api.Assertions.assertThat(status("/api/admin/anything", asString)).isIn(401, 403);
        org.assertj.core.api.Assertions.assertThat(status("/api/admin/anything", missing)).isIn(401, 403);
    }

    @Test
    void anAuthorisationHeaderThatIsNotBearerIsIgnoredNotTrusted() {
        client.get().uri("/api/admin/anything").header("Authorization", "Basic YWRtaW46YWRtaW4=").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders").header("X-User-Id", "11111111-1111-1111-1111-111111111111").header("X-Roles", "ADMIN").exchange().expectStatus().isUnauthorized();
    }
}
