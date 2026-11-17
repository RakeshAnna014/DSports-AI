package com.dsports.cricket;

import com.dsports.DSportsApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Players, teams and guest-profile claims over real HTTP with real authentication, on the whole application against a throwaway PostgreSQL: who can see
 * what, that a phone number is only ever shown to whoever created the profile, and that a claim is decided by the guest's creator and nobody else.
 */
@Tag("integration")
@SpringBootTest(classes = DSportsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.ground.expiry-initial-delay=PT24H", "app.orders.expiry-initial-delay=PT24H", "app.outbox.initial-delay=PT24H"})
class CricketPlayersAndTeamsHttpIntegrationTest {

    private static final String PASSWORD = "Sup3rSecret!pass";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private WebTestClient web;

    private record Account(UUID id, String email, String token) {
    }

    private Account register(String prefix) {
        var email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        web.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD, "firstName", prefix, "lastName", "Tester")).exchange().expectStatus().isCreated();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return new Account(UUID.fromString((String) body.get("userId")), email, (String) body.get("accessToken"));
    }

    private WebTestClient.ResponseSpec get(Account who, String uri) {
        return web.get().uri(uri).headers(h -> h.setBearerAuth(who.token())).exchange();
    }

    private WebTestClient.ResponseSpec send(String method, Account who, String uri, Object body) {
        var spec = web.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri).headers(h -> h.setBearerAuth(who.token())).contentType(MediaType.APPLICATION_JSON);
        return (body == null ? spec : spec.bodyValue(body)).exchange();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(WebTestClient.ResponseSpec r) {
        return r.expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(WebTestClient.ResponseSpec r) {
        return r.expectStatus().is2xxSuccessful().expectBodyList(Map.class).returnResult().getResponseBody().stream().map(m -> (Map<String, Object>) m).toList();
    }

    private static Map<String, Object> player(String name, String phone) {
        return phone == null ? Map.of("name", name, "role", "BOWLER", "battingHand", "RIGHT") : Map.of("name", name, "phone", phone, "role", "BOWLER", "battingHand", "RIGHT");
    }

    @Test
    void everyRouteNeedsASignedInUser() {
        for (var uri : List.of("/api/v1/players", "/api/v1/teams", "/api/v1/players/claims/incoming", "/api/v1/players/claimable?phone=9876543210")) {
            web.get().uri(uri).exchange().expectStatus().isUnauthorized();
        }
        web.post().uri("/api/v1/teams").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "X")).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void guestsTeamsAndTheirPrivacy() {
        var captain = register("captain");
        var stranger = register("stranger");

        var guest = json(send("POST", captain, "/api/v1/players", player("Arjun Kumar", "98765 43210")).expectStatus().isCreated());
        var guestId = (String) guest.get("id");
        assertThat(guest.get("phone")).isEqualTo("+919876543210");
        assertThat(guest.get("linked")).isEqualTo(false);
        send("POST", captain, "/api/v1/players", player("Arjun again", "+91 9876543210")).expectStatus().isEqualTo(409);
        send("POST", captain, "/api/v1/players", player("Bad phone", "12")).expectStatus().isBadRequest();
        send("POST", captain, "/api/v1/players", Map.of("name", " ")).expectStatus().isBadRequest();
        send("POST", stranger, "/api/v1/players", player("Arjun K (another captain)", "9876543210")).expectStatus().isCreated();

        get(stranger, "/api/v1/players/" + guestId).expectStatus().isNotFound();
        assertThat(list(get(stranger, "/api/v1/players"))).extracting(p -> p.get("name")).containsExactly("Arjun K (another captain)");

        var team = json(send("POST", captain, "/api/v1/teams", Map.of("name", "Green Lions", "city", "Chennai")).expectStatus().isCreated());
        var teamId = (String) team.get("id");
        send("POST", captain, "/api/v1/teams", Map.of("name", "green lions")).expectStatus().isEqualTo(409);
        send("POST", captain, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", guestId)).expectStatus().isCreated();
        send("POST", captain, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", guestId)).expectStatus().isEqualTo(409);
        send("POST", stranger, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", guestId)).expectStatus().isNotFound();
        var withCaptain = json(send("PUT", captain, "/api/v1/teams/" + teamId + "/captain", Map.of("playerId", guestId)).expectStatus().isOk());
        assertThat(((List<?>) withCaptain.get("members"))).hasSize(1);
        send("DELETE", captain, "/api/v1/teams/" + teamId + "/members/" + guestId, null).expectStatus().isEqualTo(409);

        get(stranger, "/api/v1/teams/" + teamId).expectStatus().isNotFound();
        assertThat(list(get(stranger, "/api/v1/teams"))).isEmpty();
        var seen = json(get(captain, "/api/v1/teams/" + teamId).expectStatus().isOk());
        assertThat(seen.get("canEdit")).isEqualTo(true);
        send("PUT", captain, "/api/v1/teams/" + teamId, Map.of("name", "Green Lions XI", "city", "Chennai", "expectedVersion", 0)).expectStatus().isEqualTo(409);
        send("PUT", captain, "/api/v1/teams/" + teamId, Map.of("name", "Green Lions XI", "city", "Chennai", "expectedVersion", seen.get("version"))).expectStatus().isOk();
    }

    @Test
    void aGuestIsClaimedByARegisteredPlayerOnlyWhenTheCreatorAgrees() {
        var phone = "9" + String.format("%09d", new java.util.Random().nextInt(1_000_000_000));
        var captain = register("captain");
        var arjun = register("arjun");
        var stranger = register("stranger");
        var guest = json(send("POST", captain, "/api/v1/players", player("Arjun Kumar", phone)).expectStatus().isCreated());
        var guestId = (String) guest.get("id");
        var team = json(send("POST", captain, "/api/v1/teams", Map.of("name", "Lions")).expectStatus().isCreated());
        var teamId = (String) team.get("id");
        send("POST", captain, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", guestId)).expectStatus().isCreated();

        // Arjun finds the profile by phone, but only as a shortened name, and asks for it.
        var hits = list(get(arjun, "/api/v1/players/claimable?phone=" + phone + ""));
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.get("displayName")).isEqualTo("Arjun K.");
            assertThat(h.get("playerId")).isEqualTo(guestId);
            assertThat(h).doesNotContainKeys("phone", "name");
        });
        get(arjun, "/api/v1/teams/" + teamId).expectStatus().isNotFound();
        var claim = json(send("POST", arjun, "/api/v1/players/" + guestId + "/claims", null).expectStatus().isCreated());
        var claimId = (String) claim.get("id");
        send("POST", arjun, "/api/v1/players/" + guestId + "/claims", null).expectStatus().isEqualTo(409);

        // Only the creator sees who is asking, and nobody else can decide.
        var incoming = list(get(captain, "/api/v1/players/claims/incoming"));
        assertThat(incoming).singleElement().satisfies(c -> {
            assertThat(c.get("claimantEmail")).isEqualTo(arjun.email());
            assertThat(c.get("claimantName")).isEqualTo("arjun Tester");
        });
        assertThat(list(get(stranger, "/api/v1/players/claims/incoming"))).isEmpty();
        send("POST", stranger, "/api/v1/players/claims/" + claimId + "/approve", null).expectStatus().isNotFound();
        send("POST", arjun, "/api/v1/players/claims/" + claimId + "/approve", null).expectStatus().isNotFound();
        assertThat(list(get(arjun, "/api/v1/players/claims/mine"))).singleElement().satisfies(c -> {
            assertThat(c.get("status")).isEqualTo("PENDING");
            assertThat(c.get("claimantEmail")).isNull();
        });

        send("POST", captain, "/api/v1/players/claims/" + claimId + "/approve", null).expectStatus().isOk();
        send("POST", captain, "/api/v1/players/claims/" + claimId + "/approve", null).expectStatus().isEqualTo(409);

        // Now the guest is Arjun's profile: he sees his team and himself, but not the phone number, and cannot edit the team.
        var mine = list(get(arjun, "/api/v1/players"));
        assertThat(mine).singleElement().satisfies(p -> {
            assertThat(p.get("mine")).isEqualTo(true);
            assertThat(p.get("phone")).isNull();
        });
        var viewed = json(get(arjun, "/api/v1/teams/" + teamId).expectStatus().isOk());
        assertThat(viewed.get("canEdit")).isEqualTo(false);
        assertThat(((List<?>) viewed.get("members"))).singleElement().satisfies(m -> assertThat(((Map<?, ?>) m).get("phone")).isNull());
        assertThat(list(get(arjun, "/api/v1/teams"))).hasSize(1);
        send("PUT", arjun, "/api/v1/teams/" + teamId, Map.of("name", "Mine now", "expectedVersion", viewed.get("version"))).expectStatus().isForbidden();
        send("POST", arjun, "/api/v1/teams/" + teamId + "/archive", null).expectStatus().isForbidden();
        assertThat(json(get(captain, "/api/v1/players/" + guestId).expectStatus().isOk()).get("phone")).isEqualTo("+91" + phone);

        // The profile is taken: nobody can claim it again, and Arjun cannot claim a second one.
        send("POST", stranger, "/api/v1/players/" + guestId + "/claims", null).expectStatus().isEqualTo(409);
        var another = json(send("POST", captain, "/api/v1/players", player("Someone Else", null)).expectStatus().isCreated());
        send("POST", arjun, "/api/v1/players/" + another.get("id") + "/claims", null).expectStatus().isEqualTo(409);
    }

    @Test
    void aRegisteredPlayerMakesTheirOwnProfileOnceAndAnOutsiderCannotDecideAClaim() {
        var captain = register("captain");
        var ben = register("ben");
        var first = json(send("POST", ben, "/api/v1/players/me", player("Ben Roy", null)).expectStatus().isOk());
        var again = json(send("POST", ben, "/api/v1/players/me", player("Another name", null)).expectStatus().isOk());
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(first.get("linked")).isEqualTo(true);
        assertThat(first.get("mine")).isEqualTo(true);

        var guest = json(send("POST", captain, "/api/v1/players", player("Cara", null)).expectStatus().isCreated());
        var cara = register("cara");
        var claim = json(send("POST", cara, "/api/v1/players/" + guest.get("id") + "/claims", null).expectStatus().isCreated());
        var outsider = register("outsider");
        send("POST", outsider, "/api/v1/players/claims/" + claim.get("id") + "/approve", null).expectStatus().isNotFound();
        assertThat(claim.get("status")).isEqualTo("PENDING");
    }
}
