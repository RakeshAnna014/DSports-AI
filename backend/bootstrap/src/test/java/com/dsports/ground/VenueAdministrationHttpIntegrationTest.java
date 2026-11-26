package com.dsports.ground;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration journey over real HTTP with real authentication, on the whole application against a throwaway PostgreSQL:
 * an administrator gives a registered user the ground-owner role (no SQL), the owner builds a venue with a court for a bookable sport, a customer finds
 * it by sport and city (any case), the administrator removes the role (the venue stops taking bookings and disappears from search), reassigns it to
 * another owner (bookable again), and every grant and removal is in the audit log.
 */
@Tag("integration")
@SpringBootTest(classes = DSportsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.ground.expiry-initial-delay=PT24H", "app.orders.expiry-initial-delay=PT24H", "app.outbox.initial-delay=PT24H"})
class VenueAdministrationHttpIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
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

    private Account register(String prefix) {
        var email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        web.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD, "firstName", "Test", "lastName", prefix)).exchange().expectStatus().isCreated();
        return login(email);
    }

    private Account login(String email) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return new Account(UUID.fromString((String) body.get("userId")), email, (String) body.get("accessToken"));
    }

    private Account administrator() {
        var account = register("admin");
        db.sql("INSERT INTO customer_roles (customer_id, role) VALUES (:id, 'ADMIN')").bind("id", account.id()).fetch().rowsUpdated().block();
        return login(account.email());
    }

    private UUID sportId(String slug) {
        return db.sql("SELECT id FROM sports WHERE slug = :slug").bind("slug", slug).map((r, m) -> r.get("id", UUID.class)).one().block();
    }

    private WebTestClient.RequestHeadersSpec<?> get(Account who, String uri) {
        return web.get().uri(uri).headers(h -> h.setBearerAuth(who.token()));
    }

    private WebTestClient.ResponseSpec post(Account who, String uri, Object body) {
        return web.post().uri(uri).headers(h -> h.setBearerAuth(who.token())).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec put(Account who, String uri, Object body) {
        return web.put().uri(uri).headers(h -> h.setBearerAuth(who.token())).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static Map<String, Object> venueBody(String name, String city) {
        return Map.of("name", name, "address", "1 Test Road", "city", city, "facilities", List.of("Parking"), "photoUrls", List.of(),
            "opensAt", "06:00", "closesAt", "22:00", "timeZone", "Asia/Kolkata");
    }

    private Map<String, Object> courtBody(String name, UUID... sports) {
        return Map.of("name", name, "surface", "synthetic", "sportIds", List.of(sports), "slotMinutes", 60, "basePrice", 800);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(WebTestClient.ResponseSpec response) {
        return response.expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jsonList(WebTestClient.ResponseSpec response) {
        return response.expectBodyList(Map.class).returnResult().getResponseBody().stream().map(m -> (Map<String, Object>) m).toList();
    }

    private static List<String> venueIds(List<Map<String, Object>> hits) {
        return hits.stream().map(v -> (String) ((Map<?, ?>) v.get("venue")).get("id")).toList();
    }

    private List<Map<String, Object>> search(String sport, String city) {
        return jsonList(web.get().uri("/api/v1/venues?sport=" + sport + "&city=" + city).exchange().expectStatus().isOk());
    }

    @Test
    void anAdministratorMakesAnOwnerWithoutSqlAndOnlyAnAdministratorCan() {
        var admin = administrator();
        var owner = register("owner");
        var customer = register("customer");

        // Nobody registered with that email: a clear message, nothing changes.
        var unknown = json(post(admin, "/api/v1/admin/ground-owners", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com")).expectStatus().isNotFound());
        assertThat(String.valueOf(unknown)).contains("USER_NOT_REGISTERED").contains("Ask them to register first");
        // An administrator cannot make themselves an owner.
        post(admin, "/api/v1/admin/ground-owners", Map.of("email", admin.email())).expectStatus().isForbidden();
        // Others cannot use the screen at all: the server is the control, not the UI.
        post(customer, "/api/v1/admin/ground-owners", Map.of("email", owner.email())).expectStatus().isForbidden();
        post(owner, "/api/v1/admin/ground-owners", Map.of("email", owner.email())).expectStatus().isForbidden();
        web.post().uri("/api/v1/admin/ground-owners").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("email", owner.email())).exchange().expectStatus().isUnauthorized();
        get(customer, "/api/v1/admin/ground-owners").exchange().expectStatus().isForbidden();
        get(customer, "/api/v1/admin/ground-owners/audit").exchange().expectStatus().isForbidden();
        // An owner route is closed until the role is granted ...
        get(owner, "/api/v1/owner/venues").exchange().expectStatus().isForbidden();

        post(admin, "/api/v1/admin/ground-owners", Map.of("email", owner.email().toUpperCase())).expectStatus().isCreated();
        post(admin, "/api/v1/admin/ground-owners", Map.of("email", owner.email())).expectStatus().isOk();

        // ... and opens after they sign in again (the role is in their new token).
        var renewed = login(owner.email());
        get(renewed, "/api/v1/owner/venues").exchange().expectStatus().isOk();
        var owners = jsonList(get(admin, "/api/v1/admin/ground-owners").exchange().expectStatus().isOk());
        assertThat(owners).extracting(o -> o.get("email")).contains(owner.email());
        assertThat(owners).extracting(o -> o.get("email")).doesNotContain(admin.email(), customer.email());
        var audit = jsonList(get(admin, "/api/v1/admin/ground-owners/audit").exchange().expectStatus().isOk());
        assertThat(audit).filteredOn(a -> owner.email().equals(a.get("targetEmail"))).hasSize(1)
            .allSatisfy(a -> assertThat(a.get("actorId")).isEqualTo(admin.id().toString()));
        assertThat(audit).filteredOn(a -> owner.email().equals(a.get("targetEmail"))).extracting(a -> a.get("action")).containsExactly("GRANTED");

        // Removing the role records it too and closes the owner routes for new tokens.
        get(admin, "/api/v1/admin/ground-owners").exchange().expectStatus().isOk();
        web.delete().uri("/api/v1/admin/ground-owners/" + owner.id()).headers(h -> h.setBearerAuth(admin.token())).exchange().expectStatus().isNoContent();
        web.delete().uri("/api/v1/admin/ground-owners/" + owner.id()).headers(h -> h.setBearerAuth(admin.token())).exchange().expectStatus().isNotFound();
        get(login(owner.email()), "/api/v1/owner/venues").exchange().expectStatus().isForbidden();
        var after = jsonList(get(admin, "/api/v1/admin/ground-owners/audit").exchange().expectStatus().isOk());
        assertThat(after).filteredOn(a -> owner.email().equals(a.get("targetEmail"))).extracting(a -> a.get("action")).containsExactly("REVOKED", "GRANTED");
    }

    @Test
    void theWholeVenueLifecycleThroughTheApiFromGrantToRemovalAndReassignment() {
        var admin = administrator();
        var first = register("first");
        var second = register("second");
        var customer = register("customer");
        post(admin, "/api/v1/admin/ground-owners", Map.of("email", first.email())).expectStatus().isCreated();
        post(admin, "/api/v1/admin/ground-owners", Map.of("email", second.email())).expectStatus().isCreated();
        first = login(first.email());
        second = login(second.email());
        var pickleball = sportId("pickleball");
        var cricket = sportId("cricket");
        var running = sportId("running");

        // The owner builds a venue and a court; only bookable catalogue sports are accepted.
        var venue = json(post(first, "/api/v1/owner/venues", Map.of("venue", venueBody("Hub " + UUID.randomUUID(), "Chennai"))).expectStatus().isCreated());
        var venueId = (String) ((Map<?, ?>) venue.get("venue")).get("id");
        post(first, "/api/v1/owner/venues/" + venueId + "/courts", courtBody("Court 1", running)).expectStatus().isEqualTo(422);
        var court = json(post(first, "/api/v1/owner/venues/" + venueId + "/courts", courtBody("Court 1", pickleball, cricket)).expectStatus().isCreated());
        var courtId = (String) court.get("id");
        post(first, "/api/v1/owner/venues/" + venueId + "/courts", courtBody("COURT 1", cricket)).expectStatus().isEqualTo(409);

        // A customer finds it by sport and city, whatever the case of the city; another owner's venue is off limits.
        assertThat(venueIds(search(pickleball.toString(), "CHENNAI"))).contains(venueId);
        assertThat(venueIds(search(pickleball.toString(), "chennai"))).contains(venueId);
        assertThat(venueIds(search(sportId("badminton").toString(), "Chennai"))).doesNotContain(venueId);
        get(second, "/api/v1/owner/venues/" + venueId).exchange().expectStatus().isForbidden();
        var day = LocalDate.now(IST).plusDays(3).toString();
        assertThat(jsonList(get(customer, "/api/v1/venues/" + venueId + "/courts/" + courtId + "/slots?date=" + day).exchange().expectStatus().isOk())).hasSize(16);

        // The customer can hold a slot for a sport the court offers, but not one it does not.
        var start = LocalDate.now(IST).plusDays(3).atTime(9, 0).atZone(IST).toInstant().toString();
        var end = LocalDate.now(IST).plusDays(3).atTime(10, 0).atZone(IST).toInstant().toString();
        web.post().uri("/api/v1/bookings").headers(h -> {
            h.setBearerAuth(customer.token());
            h.set("Idempotency-Key", UUID.randomUUID().toString());
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("courtId", courtId, "sportId", sportId("football"), "startAt", start, "endAt", end))
            .exchange().expectStatus().isEqualTo(422);

        // The administrator removes the owner's role: the venue is flagged, hidden from search, and takes no new bookings.
        web.delete().uri("/api/v1/admin/ground-owners/" + first.id()).headers(h -> h.setBearerAuth(admin.token())).exchange().expectStatus().isNoContent();
        assertThat(venueIds(search(pickleball.toString(), "chennai"))).doesNotContain(venueId);
        var detail = json(get(customer, "/api/v1/venues/" + venueId).exchange().expectStatus().isOk());
        assertThat(detail.get("acceptingBookings")).isEqualTo(false);
        var seenByAdmin = json(get(admin, "/api/v1/admin/venues/" + venueId).exchange().expectStatus().isOk());
        assertThat(seenByAdmin.get("ownerEligible")).isEqualTo(false);
        assertThat(seenByAdmin.get("ownerId")).isEqualTo(first.id().toString());
        var refused = json(web.post().uri("/api/v1/bookings").headers(h -> {
            h.setBearerAuth(customer.token());
            h.set("Idempotency-Key", UUID.randomUUID().toString());
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("courtId", courtId, "sportId", pickleball, "startAt", start, "endAt", end))
            .exchange().expectStatus().isEqualTo(409));
        assertThat(String.valueOf(refused)).contains("VENUE_NOT_ACCEPTING_BOOKINGS");
        // A new sign-in of the removed owner no longer carries the role, so the owner routes are closed to them.
        get(login(first.email()), "/api/v1/owner/venues").exchange().expectStatus().isForbidden();

        // An administrator edits the venue on the owner's behalf and reassigns it to another ground owner; it is bookable again.
        var version = seenByAdmin.get("version");
        put(admin, "/api/v1/admin/venues/" + venueId, Map.of("venue", venueBody("Hub reassigned", "Chennai"), "active", true, "expectedVersion", version, "ownerId", UUID.randomUUID()))
            .expectStatus().isEqualTo(409);
        var moved = json(put(admin, "/api/v1/admin/venues/" + venueId,
            Map.of("venue", venueBody("Hub reassigned", "Chennai"), "active", true, "expectedVersion", version, "ownerId", second.id())).expectStatus().isOk());
        assertThat(moved.get("ownerId")).isEqualTo(second.id().toString());
        assertThat(moved.get("ownerEligible")).isEqualTo(true);
        assertThat(venueIds(search(pickleball.toString(), "chennai"))).contains(venueId);
        var held = json(web.post().uri("/api/v1/bookings").headers(h -> {
            h.setBearerAuth(customer.token());
            h.set("Idempotency-Key", UUID.randomUUID().toString());
        }).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("courtId", courtId, "sportId", pickleball, "startAt", start, "endAt", end))
            .exchange().expectStatus().isCreated());
        assertThat(held.get("sportName")).isEqualTo("Pickleball");
        assertThat(held.get("courtName")).isEqualTo("Court 1");
        // The new owner now manages it; the old one does not.
        assertThat(venueIds(jsonList(get(second, "/api/v1/owner/venues").exchange().expectStatus().isOk()))).contains(venueId);
        get(second, "/api/v1/owner/venues/" + venueId).exchange().expectStatus().isOk();
    }

    @Test
    void anAdministratorCanCreateAVenueAndCourtsForAnOwnerButOnlyForSomeoneWhoHoldsTheRole() {
        var admin = administrator();
        var owner = register("owner");
        var plain = register("plain");
        post(admin, "/api/v1/admin/ground-owners", Map.of("email", owner.email())).expectStatus().isCreated();

        post(admin, "/api/v1/admin/venues", Map.of("venue", venueBody("No owner", "Delhi"), "ownerId", plain.id())).expectStatus().isEqualTo(409);
        post(admin, "/api/v1/admin/venues", Map.of("venue", venueBody("No owner given", "Delhi"))).expectStatus().isBadRequest();
        var venue = json(post(admin, "/api/v1/admin/venues", Map.of("venue", venueBody("Built by admin", "Delhi"), "ownerId", owner.id())).expectStatus().isCreated());
        var venueId = (String) ((Map<?, ?>) venue.get("venue")).get("id");
        assertThat(venue.get("ownerId")).isEqualTo(owner.id().toString());
        post(admin, "/api/v1/admin/venues/" + venueId + "/courts", courtBody("Court A", sportId("badminton"))).expectStatus().isCreated();

        var renewed = login(owner.email());
        var mine = jsonList(get(renewed, "/api/v1/owner/venues").exchange().expectStatus().isOk());
        assertThat(venueIds(mine)).contains(venueId);
        post(renewed, "/api/v1/admin/venues", Map.of("venue", venueBody("Sneaky", "Delhi"), "ownerId", owner.id())).expectStatus().isForbidden();
    }
}
