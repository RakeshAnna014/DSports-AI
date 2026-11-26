package com.dsports.cricket;

import com.dsports.DSportsApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Match setup over real HTTP with real authentication, on the whole application against a throwaway PostgreSQL: creating a match between two teams,
 * a real confirmed cricket venue booking, the toss, both playing XIs, scorers found by email, and who can see or change what.
 */
@Tag("integration")
@SpringBootTest(classes = DSportsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.ground.expiry-initial-delay=PT24H", "app.orders.expiry-initial-delay=PT24H", "app.outbox.initial-delay=PT24H"})
class CricketMatchSetupHttpIntegrationTest {

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
    @Autowired private DatabaseClient db;

    private record Account(UUID id, String email, String token) {
    }

    private record Squad(String teamId, List<String> playerIds) {
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

    private Squad squad(Account owner, String teamName, int size) {
        var team = json(send("POST", owner, "/api/v1/teams", Map.of("name", teamName + " " + UUID.randomUUID().toString().substring(0, 6))).expectStatus().isCreated());
        var teamId = (String) team.get("id");
        var ids = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            var p = json(send("POST", owner, "/api/v1/players", Map.of("name", teamName + " player " + i)).expectStatus().isCreated());
            send("POST", owner, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", p.get("id"))).expectStatus().isCreated();
            ids.add((String) p.get("id"));
        }
        return new Squad(teamId, ids);
    }

    private UUID confirmedBooking(Account customer, String sportSlug, String status) {
        var venue = UUID.randomUUID();
        var court = UUID.randomUUID();
        var booking = UUID.randomUUID();
        var sportId = db.sql("SELECT id FROM sports WHERE slug = :slug").bind("slug", sportSlug).map((r, m) -> r.get("id", UUID.class)).one().block();
        db.sql("INSERT INTO venues (id, owner_id, name, address, city, opens_at, closes_at) VALUES (:id, :owner, 'Green Park Hub', '1 Road', 'Pune', '06:00', '22:00')")
            .bind("id", venue).bind("owner", UUID.randomUUID()).fetch().rowsUpdated().block();
        db.sql("INSERT INTO courts (id, venue_id, name, sport_ids, slot_minutes, base_price) VALUES (:id, :venue, 'Court 1', ARRAY[:sport]::uuid[], 60, 1000)")
            .bind("id", court).bind("venue", venue).bind("sport", sportId).fetch().rowsUpdated().block();
        var start = Instant.now().plusSeconds(3 * 86400);
        db.sql("""
                INSERT INTO ground_bookings (id, booking_number, venue_id, court_id, sport_id, user_id, start_at, end_at, price, currency, status, idempotency_key, held_until)
                VALUES (:id, :number, :venue, :court, :sport, :user, :start, :end, 1000, 'INR', :status, :key, now())
                """)
            .bind("id", booking).bind("number", "GB-" + booking.toString().substring(0, 8)).bind("venue", venue).bind("court", court).bind("sport", sportId)
            .bind("user", customer.id()).bind("start", start).bind("end", start.plusSeconds(3600)).bind("status", status).bind("key", booking.toString())
            .fetch().rowsUpdated().block();
        return booking;
    }

    @Test
    void everyMatchRouteNeedsASignedInUser() {
        web.get().uri("/api/v1/matches").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/v1/matches/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        web.post().uri("/api/v1/matches").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anOrganiserSetsUpAMatchFromTheTeamsToTheXisAndTheTossWithAScorer() {
        var organiser = register("organiser");
        var scorer = register("scorer");
        var stranger = register("stranger");
        var lions = squad(organiser, "Lions", 11);
        var tigers = squad(organiser, "Tigers", 9);

        // Validation and visibility first.
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", lions.teamId(), "overs", 20, "ballType", "TENNIS")).expectStatus().isEqualTo(409);
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 0, "ballType", "TENNIS")).expectStatus().isBadRequest();
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 20, "ballType", "GOLF")).expectStatus().isBadRequest();
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 20, "ballType", "TENNIS",
            "rules", Map.of("ballsPerOver", 3))).expectStatus().isBadRequest();
        send("POST", stranger, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 20, "ballType", "TENNIS")).expectStatus().isNotFound();

        var match = json(send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 12, "ballType", "TENNIS",
            "city", "Chennai", "umpires", "Ravi and Sam", "rules", Map.of("playersPerSide", 9, "noBallPenalty", 2))).expectStatus().isCreated());
        var matchId = (String) match.get("id");
        assertThat(match.get("status")).isEqualTo("SCHEDULED");
        assertThat(match.get("canManage")).isEqualTo(true);
        assertThat(((Map<?, ?>) match.get("rules")).get("playersPerSide")).isEqualTo(9);
        assertThat(((Map<?, ?>) match.get("rules")).get("noBallPenalty")).isEqualTo(2);
        assertThat(((Map<?, ?>) match.get("rules")).get("widePenalty")).isEqualTo(1);
        get(stranger, "/api/v1/matches/" + matchId).expectStatus().isNotFound();
        assertThat(list(get(stranger, "/api/v1/matches"))).isEmpty();

        // Scorers are found by email; an unknown email says to register first.
        send("POST", organiser, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com")).expectStatus().isNotFound();
        var withScorer = json(send("POST", organiser, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", scorer.email().toUpperCase())).expectStatus().isCreated());
        assertThat(((List<?>) withScorer.get("scorers"))).singleElement().satisfies(s -> assertThat(((Map<?, ?>) s).get("email")).isEqualTo(scorer.email()));
        send("POST", organiser, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", scorer.email())).expectStatus().isEqualTo(409);
        send("POST", stranger, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", stranger.email())).expectStatus().isNotFound();
        send("POST", scorer, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", stranger.email())).expectStatus().isForbidden();

        // The scorer records the toss and the XIs.
        send("POST", scorer, "/api/v1/matches/" + matchId + "/toss", Map.of("winnerTeamId", UUID.randomUUID().toString(), "decision", "BAT")).expectStatus().isBadRequest();
        var tossed = json(send("POST", scorer, "/api/v1/matches/" + matchId + "/toss", Map.of("winnerTeamId", tigers.teamId(), "decision", "BOWL")).expectStatus().isOk());
        assertThat(tossed.get("status")).isEqualTo("TOSS_DONE");
        assertThat(tossed.get("battingFirstTeamId")).isEqualTo(lions.teamId());
        send("PUT", scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", lions.teamId(), "playerIds", lions.playerIds().subList(0, 9))).expectStatus().isOk();
        send("PUT", scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", lions.teamId(), "playerIds", lions.playerIds())).expectStatus().isEqualTo(409);
        send("PUT", scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", tigers.teamId(), "playerIds", lions.playerIds().subList(0, 3))).expectStatus().isEqualTo(409);
        send("PUT", scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", tigers.teamId(), "playerIds", List.of(tigers.playerIds().get(0), tigers.playerIds().get(0)))).expectStatus().isEqualTo(409);
        var ready = json(send("PUT", scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", tigers.teamId(), "playerIds", tigers.playerIds())).expectStatus().isOk());
        assertThat(ready.get("readyToStart")).isEqualTo(true);
        assertThat(((List<?>) ((Map<?, ?>) ready.get("teamB")).get("playingXi"))).hasSize(9);
        assertThat(ready.get("canManage")).isEqualTo(false);
        assertThat(ready.get("canScore")).isEqualTo(true);

        // Settings are the creator's alone, version-checked.
        var seen = json(get(organiser, "/api/v1/matches/" + matchId).expectStatus().isOk());
        var update = Map.of("overs", 10, "ballType", "LEATHER", "city", "Goa", "expectedVersion", seen.get("version"));
        send("PUT", scorer, "/api/v1/matches/" + matchId, update).expectStatus().isForbidden();
        send("PUT", organiser, "/api/v1/matches/" + matchId, Map.of("overs", 10, "ballType", "LEATHER", "expectedVersion", 0)).expectStatus().isEqualTo(409);
        var updated = json(send("PUT", organiser, "/api/v1/matches/" + matchId, update).expectStatus().isOk());
        assertThat(updated.get("overs")).isEqualTo(10);
        assertThat(updated.get("city")).isEqualTo("Goa");

        assertThat(list(get(organiser, "/api/v1/matches"))).extracting(m -> m.get("id")).contains(matchId);
        assertThat(list(get(scorer, "/api/v1/matches"))).extracting(m -> m.get("id")).contains(matchId);
        send("DELETE", organiser, "/api/v1/matches/" + matchId + "/scorers/" + scorer.id(), null).expectStatus().isOk();
        get(scorer, "/api/v1/matches/" + matchId).expectStatus().isNotFound();
    }

    @Test
    void aTeamMemberWithALinkedAccountSeesTheMatchButCannotChangeIt() {
        var organiser = register("organiser");
        var player = register("player");
        var lions = squad(organiser, "Lions", 3);
        var tigers = squad(organiser, "Tigers", 3);
        var match = json(send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 5, "ballType", "TENNIS")).expectStatus().isCreated());
        var matchId = (String) match.get("id");
        get(player, "/api/v1/matches/" + matchId).expectStatus().isNotFound();

        // The player claims one of Lions' guests; the organiser approves; now the player has a team in the match.
        var claim = json(send("POST", player, "/api/v1/players/" + lions.playerIds().get(0) + "/claims", null).expectStatus().isCreated());
        send("POST", organiser, "/api/v1/players/claims/" + claim.get("id") + "/approve", null).expectStatus().isOk();

        var seen = json(get(player, "/api/v1/matches/" + matchId).expectStatus().isOk());
        assertThat(seen.get("canScore")).isEqualTo(false);
        assertThat(seen.get("canManage")).isEqualTo(false);
        assertThat(((List<?>) seen.get("scorers"))).isEmpty();
        send("POST", player, "/api/v1/matches/" + matchId + "/toss", Map.of("winnerTeamId", lions.teamId(), "decision", "BAT")).expectStatus().isForbidden();
        assertThat(list(get(player, "/api/v1/matches"))).extracting(m -> m.get("id")).contains(matchId);
    }

    @Test
    void aConfirmedCricketBookingGivesTheMatchItsVenueAndHostsOnlyOneMatch() {
        var organiser = register("organiser");
        var other = register("other");
        var lions = squad(organiser, "Lions", 2);
        var tigers = squad(organiser, "Tigers", 2);
        var booking = confirmedBooking(organiser, "cricket", "CONFIRMED");
        Map<String, Object> request = Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 6, "ballType", "TENNIS", "bookingId", booking.toString());

        var match = json(send("POST", organiser, "/api/v1/matches", request).expectStatus().isCreated());

        var venue = (Map<?, ?>) match.get("venue");
        assertThat(venue.get("name")).isEqualTo("Green Park Hub");
        assertThat(venue.get("bookingId")).isEqualTo(booking.toString());
        assertThat(match.get("city")).isEqualTo("Pune");
        assertThat(match.get("scheduledAt")).isNotNull();
        send("POST", organiser, "/api/v1/matches", request).expectStatus().isEqualTo(409);
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 6, "ballType", "TENNIS",
            "bookingId", confirmedBooking(organiser, "football", "CONFIRMED").toString())).expectStatus().isEqualTo(409);
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 6, "ballType", "TENNIS",
            "bookingId", confirmedBooking(organiser, "cricket", "HELD").toString())).expectStatus().isEqualTo(409);
        send("POST", organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 6, "ballType", "TENNIS",
            "bookingId", confirmedBooking(other, "cricket", "CONFIRMED").toString())).expectStatus().isEqualTo(409);
    }
}
