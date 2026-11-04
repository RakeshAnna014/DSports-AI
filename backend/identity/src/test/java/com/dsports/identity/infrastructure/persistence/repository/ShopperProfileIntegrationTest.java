package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.SportTaxonomyPort;
import com.dsports.identity.application.usecase.ShopperProfileUseCase;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.profile.AgeBand;
import com.dsports.identity.domain.profile.ProfileKind;
import com.dsports.identity.domain.profile.ShoppingPreferences;
import com.dsports.identity.domain.profile.UsageLevel;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shopper profiles against a real PostgreSQL: the V46 migration, ownership, preferences round trip, the family limit and cascade deletes. */
@Tag("integration")
class ShopperProfileIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static ShopperProfileUseCase profiles;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        var cf = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost()
            + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(cf);
        SportTaxonomyPort taxonomy = () -> Mono.just(Set.of("Cricket", "Football", "Badminton"));
        profiles = new ShopperProfileUseCase(new ShopperProfileR2dbcAdapter(db), taxonomy, Clock.system(ZoneOffset.UTC));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static ShoppingPreferences prefs(List<String> sports) {
        return ShoppingPreferences.normalised(sports, List.of("SG", "sg", "Kookaburra"), List.of("Bats"), List.of("India"), UsageLevel.INTERMEDIATE,
            new BigDecimal("500"), new BigDecimal("8000"), Map.of("shoe", "UK 8", "fit", "regular"), Map.of("battinghand", "right"));
    }

    @Test
    void the_owner_profile_is_created_once_even_under_concurrent_first_use() {
        var user = UUID.randomUUID();
        var all = Flux.range(0, 8).flatMap(i -> profiles.overview(user)).collectList().block();
        assertThat(all).allMatch(o -> o.profiles().size() == 1);
        var overview = profiles.overview(user).block();
        assertThat(overview.profiles().get(0).kind()).isEqualTo(ProfileKind.OWNER);
        assertThat(overview.activeProfileId()).isEqualTo(overview.profiles().get(0).id());
        assertThat(overview.profiles().get(0).preferences().isEmpty()).isTrue();
    }

    @Test
    void preferences_round_trip_are_normalised_and_sports_use_the_catalog_spelling() {
        var user = UUID.randomUUID();
        var own = profiles.overview(user).block().profiles().get(0);
        var saved = profiles.updatePreferences(user, own.id(), prefs(List.of("cricket", "Football")), null).block();
        assertThat(saved.preferences().sports()).containsExactly("Cricket", "Football");
        var loaded = profiles.get(user, own.id()).block();
        assertThat(loaded.preferences().favoriteBrands()).containsExactly("SG", "Kookaburra");   // de-duplicated, case-insensitively
        assertThat(loaded.preferences().priceMax()).isEqualByComparingTo("8000");
        assertThat(loaded.preferences().sizes()).containsEntry("shoe", "UK 8");
        assertThat(loaded.preferences().usageLevel()).isEqualTo(UsageLevel.INTERMEDIATE);
        var reset = profiles.resetPreferences(user, own.id()).block();
        assertThat(reset.preferences().isEmpty()).isTrue();
    }

    @Test
    void a_sport_the_catalog_does_not_sell_is_rejected_and_optional_fields_stay_optional() {
        var user = UUID.randomUUID();
        var own = profiles.overview(user).block().profiles().get(0);
        assertThatThrownBy(() -> profiles.updatePreferences(user, own.id(), prefs(List.of("Quidditch")), null).block())
            .isInstanceOf(IdentityDomainException.class).extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        var onlyOneField = ShoppingPreferences.normalised(null, null, null, null, null, null, null, null, null);
        assertThat(profiles.updatePreferences(user, own.id(), onlyOneField, null).block().preferences().isEmpty()).isTrue();
    }

    @Test
    void another_accounts_profile_is_indistinguishable_from_a_missing_one() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var alicesOwn = profiles.overview(alice).block().profiles().get(0);
        var child = profiles.createFamily(alice, "Aarav", "Son", AgeBand.CHILD_8_12, ShoppingPreferences.empty(), true).block();
        for (var id : List.of(alicesOwn.id(), child.id(), UUID.randomUUID())) {
            assertThatThrownBy(() -> profiles.get(bob, id).block()).isInstanceOf(IdentityDomainException.class).extracting("errorCode").isEqualTo(ErrorCode.PROFILE_NOT_FOUND);
            assertThatThrownBy(() -> profiles.select(bob, id).block()).isInstanceOf(IdentityDomainException.class);
            assertThatThrownBy(() -> profiles.updatePreferences(bob, id, ShoppingPreferences.empty(), null).block()).isInstanceOf(IdentityDomainException.class);
            assertThatThrownBy(() -> profiles.deleteFamily(bob, id).block()).isInstanceOf(IdentityDomainException.class);
        }
        assertThat(profiles.get(alice, child.id()).block().displayName()).isEqualTo("Aarav");   // untouched
    }

    @Test
    void family_profiles_are_limited_switchable_and_deleting_the_active_one_falls_back_to_the_owner() {
        var user = UUID.randomUUID();
        var own = profiles.overview(user).block().profiles().get(0);
        var first = profiles.createFamily(user, "Meera", "Daughter", AgeBand.TEEN_13_17, prefs(List.of("Badminton")), true).block();
        assertThat(first.kind()).isEqualTo(ProfileKind.FAMILY);
        assertThat(profiles.select(user, first.id()).block().id()).isEqualTo(first.id());
        assertThat(profiles.active(user).block().preferences().sports()).containsExactly("Badminton");
        profiles.deleteFamily(user, first.id()).block();
        assertThat(profiles.active(user).block().id()).isEqualTo(own.id());
        assertThat(db.sql("SELECT COUNT(*) AS n FROM shopper_profiles WHERE id = :id").bind("id", first.id()).map((r, m) -> r.get("n", Long.class)).one().block()).isZero();

        for (int i = 0; i < ShopperProfileUseCase.MAX_FAMILY_PROFILES; i++) {
            profiles.createFamily(user, "Member " + i, null, AgeBand.ADULT, ShoppingPreferences.empty(), true).block();
        }
        assertThatThrownBy(() -> profiles.createFamily(user, "One too many", null, AgeBand.ADULT, ShoppingPreferences.empty(), true).block())
            .isInstanceOf(IdentityDomainException.class).extracting("errorCode").isEqualTo(ErrorCode.MAX_FAMILY_PROFILES_EXCEEDED);
        assertThatThrownBy(() -> profiles.deleteFamily(user, own.id()).block()).isInstanceOf(IdentityDomainException.class);
    }

    @Test
    void a_family_member_needs_a_name_and_an_age_band_and_the_database_refuses_an_inverted_price_range() {
        var user = UUID.randomUUID();
        assertThatThrownBy(() -> profiles.createFamily(user, " ", null, AgeBand.ADULT, ShoppingPreferences.empty(), true).block()).isInstanceOf(IdentityDomainException.class);
        assertThatThrownBy(() -> profiles.createFamily(user, "Kid", null, null, ShoppingPreferences.empty(), true).block()).isInstanceOf(IdentityDomainException.class);
        assertThatThrownBy(() -> ShoppingPreferences.normalised(null, null, null, null, null, new BigDecimal("900"), new BigDecimal("100"), null, null))
            .isInstanceOf(IdentityDomainException.class);
        var own = profiles.overview(user).block().profiles().get(0);
        assertThatThrownBy(() -> db.sql("UPDATE shopper_profiles SET price_min = 900, price_max = 100 WHERE id = :id").bind("id", own.id()).fetch().rowsUpdated().block())
            .hasMessageContaining("ck_shopper_profile_price");
    }
}
