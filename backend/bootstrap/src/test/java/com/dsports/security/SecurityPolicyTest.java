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
import org.junit.jupiter.params.provider.ValueSource;
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
                "/api/inventory/x", "/api/prices/x", "/api/v1/orders", "/api/admin/anything", "/api/v1/admin/sales/lines", "/api/v1/support/staff/cases", "/api/v1/cart", "/api-docs/openapi", "/api/v1/venues", "/api/v1/venues/x/courts/y/slots", "/api/v1/owner/venues", "/api/v1/owner/bookings", "/api/v1/admin/venues", "/api/v1/admin/ground-owners", "/api/v1/admin/ground-owners/audit", "/api/v1/players", "/api/v1/teams", "/api/v1/matches", "/api/v1/matches/x", "/api/v1/matches/x/scorecard", "/api/v1/public/matches/live", "/api/v1/matches/x/scoring", "/api/v1/admin/matches/x/scoring/unlock", "/api/v1/me/bookings",
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
            // ground booking: browsing is public, bookings need an account, owner routes need the owner role
            "/api/v1/venues,anonymous,200", "/api/v1/venues/x/courts/y/slots,anonymous,200", "/api/v1/me/bookings,anonymous,401", "/api/v1/me/bookings,customer,200",
            "/api/v1/owner/venues,anonymous,401", "/api/v1/owner/venues,customer,403", "/api/v1/owner/venues,admin,403", "/api/v1/owner/venues,owner,200",
            // venue administration is for administrators only (an owner cannot use it to reach other owners' venues)
            "/api/v1/admin/venues,anonymous,401", "/api/v1/admin/venues,customer,403", "/api/v1/admin/venues,owner,403", "/api/v1/admin/venues,admin,200",
            // cricket players and teams: signed-in users only (the use cases decide who sees what)
            "/api/v1/players,anonymous,401", "/api/v1/players,customer,200", "/api/v1/teams,anonymous,401", "/api/v1/teams,customer,200", "/api/v1/matches,anonymous,401", "/api/v1/matches,customer,200",
            // cricket public scorecards: exactly two read-only routes need no sign-in, decided by path alone (a started match's scorecard, and the live list on its own path);
            // /api/v1/matches is "my matches" whatever its query string says, and a match's setup and every other path under it stay behind a sign-in
            "/api/v1/matches/x/scorecard,anonymous,200", "/api/v1/matches/x/scorecard,customer,200", "/api/v1/public/matches/live,anonymous,200", "/api/v1/public/matches/live?city=Chennai&page=1&size=5,anonymous,200",
            "/api/v1/public/matches/live,customer,200", "/api/v1/public/matches/live/x,anonymous,401", "/api/v1/public/matches,anonymous,401", "/api/v1/public/matches/livex,anonymous,401",
            "/api/v1/matches?city=Chennai,anonymous,401", "/api/v1/matches?Status=LIVE,anonymous,401", "/api/v1/matches?status=LIVE,customer,200",
            "/api/v1/matches/x,anonymous,401", "/api/v1/matches/x,customer,200", "/api/v1/matches/x/y/scorecard,anonymous,401", "/api/v1/matches/x/scorecards,anonymous,401",
            // cricket scoring: signed-in users only (the use case decides who may score); unlocking a locked match is for administrators only
            "/api/v1/matches/x/scoring,anonymous,401", "/api/v1/matches/x/scoring,customer,200", "/api/v1/admin/matches/x/scoring/unlock,anonymous,401",
            "/api/v1/admin/matches/x/scoring/unlock,customer,403", "/api/v1/admin/matches/x/scoring/unlock,owner,403", "/api/v1/admin/matches/x/scoring/unlock,support,403", "/api/v1/admin/matches/x/scoring/unlock,admin,200",
            // granting and removing the ground-owner role, and its audit log: administrators only
            "/api/v1/admin/ground-owners,anonymous,401", "/api/v1/admin/ground-owners,customer,403", "/api/v1/admin/ground-owners,owner,403", "/api/v1/admin/ground-owners,support,403", "/api/v1/admin/ground-owners,admin,200",
            "/api/v1/admin/ground-owners/audit,customer,403", "/api/v1/admin/ground-owners/audit,owner,403", "/api/v1/admin/ground-owners/audit,admin,200",
            "/api/v1/owner/bookings,anonymous,401", "/api/v1/owner/bookings,customer,403", "/api/v1/owner/bookings,admin,403", "/api/v1/owner/bookings,owner,200",
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
            case "owner" -> valid("CUSTOMER", "GROUND_OWNER");
            default -> throw new IllegalArgumentException(who);
        };
        org.assertj.core.api.Assertions.assertThat(status(path, bearer)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} without a token -> 401")
    @CsvSource({"POST,/api/v1/matches/x/scorecard", "PUT,/api/v1/matches/x/scorecard", "DELETE,/api/v1/matches/x/scorecard", "POST,/api/v1/public/matches/live", "PUT,/api/v1/public/matches/live",
            "DELETE,/api/v1/public/matches/live", "POST,/api/v1/matches", "HEAD,/api/v1/matches/x/scorecard"})
    void thePublicCricketRoutesAreReadOnlyOtherMethodsStillNeedASignIn(String method, String path) {
        client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).exchange().expectStatus().isUnauthorized();
    }

    /** The old shared-path design opened /api/v1/matches when a status parameter was present; the path alone decides now, so no spelling of the parameter can open it. */
    @ParameterizedTest(name = "/api/v1/matches?{0} without a token -> 401")
    @ValueSource(strings = {"status=LIVE", "status=live", "status=%4CIVE", "status=LIVE&status=X", "status=LIVE,FINISHED", "status=LIVE&city=Chennai", "status=", "status", "STATUS=LIVE",
            "status=LIVE&size=5&page=0"})
    void theMyMatchesPathNeedsASignInWhateverTheQueryStringSays(String query) {
        client.get().uri(java.net.URI.create("/api/v1/matches?" + query)).exchange().expectStatus().isUnauthorized();
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
