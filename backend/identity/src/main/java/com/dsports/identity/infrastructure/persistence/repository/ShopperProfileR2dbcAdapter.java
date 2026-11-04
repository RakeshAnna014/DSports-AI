package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.ShopperProfileRepository;
import com.dsports.identity.domain.profile.AgeBand;
import com.dsports.identity.domain.profile.ProfileKind;
import com.dsports.identity.domain.profile.ShopperProfile;
import com.dsports.identity.domain.profile.ShoppingPreferences;
import com.dsports.identity.domain.profile.UsageLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShopperProfileR2dbcAdapter implements ShopperProfileRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};
    private static final String COLUMNS = "id, owner_user_id, kind, display_name, relationship, age_band, sports, favorite_brands, favorite_categories, "
        + "favorite_teams, usage_level, price_min, price_max, sizes, attributes, personalization_enabled, created_at, updated_at";

    private final DatabaseClient db;

    public ShopperProfileR2dbcAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<ShopperProfile> findByOwner(UUID ownerUserId) {
        return db.sql("SELECT " + COLUMNS + " FROM shopper_profiles WHERE owner_user_id = :o ORDER BY (kind = 'OWNER') DESC, created_at, id")
            .bind("o", ownerUserId).map((row, meta) -> map(row)).all();
    }

    @Override
    public Mono<ShopperProfile> findByIdAndOwner(UUID id, UUID ownerUserId) {
        return db.sql("SELECT " + COLUMNS + " FROM shopper_profiles WHERE id = :id AND owner_user_id = :o")
            .bind("id", id).bind("o", ownerUserId).map((row, meta) -> map(row)).one();
    }

    @Override
    public Mono<ShopperProfile> ensureOwnerProfile(ShopperProfile c) {
        return db.sql("INSERT INTO shopper_profiles (id, owner_user_id, kind, display_name, created_at, updated_at) "
                + "VALUES (:id, :o, 'OWNER', :name, :at, :at) ON CONFLICT (owner_user_id) WHERE kind = 'OWNER' DO NOTHING")
            .bind("id", c.id()).bind("o", c.ownerUserId()).bind("name", c.displayName()).bind("at", c.createdAt()).fetch().rowsUpdated()
            .then(db.sql("SELECT " + COLUMNS + " FROM shopper_profiles WHERE owner_user_id = :o AND kind = 'OWNER'")
                .bind("o", c.ownerUserId()).map((row, meta) -> map(row)).one());
    }

    @Override
    public Mono<Long> countFamily(UUID ownerUserId) {
        return db.sql("SELECT COUNT(*) AS n FROM shopper_profiles WHERE owner_user_id = :o AND kind = 'FAMILY'")
            .bind("o", ownerUserId).map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Void> save(ShopperProfile p) {
        var pr = p.preferences();
        var spec = db.sql("INSERT INTO shopper_profiles (" + COLUMNS + ") VALUES (:id, :o, :kind, :name, :rel, :band, :sports, :brands, :cats, :teams, "
                + ":usage, :pmin, :pmax, :sizes, :attrs, :pers, :created, :updated) ON CONFLICT (id) DO UPDATE SET display_name = :name, relationship = :rel, "
                + "age_band = :band, sports = :sports, favorite_brands = :brands, favorite_categories = :cats, favorite_teams = :teams, usage_level = :usage, "
                + "price_min = :pmin, price_max = :pmax, sizes = :sizes, attributes = :attrs, personalization_enabled = :pers, updated_at = :updated")
            .bind("id", p.id()).bind("o", p.ownerUserId()).bind("kind", p.kind().name()).bind("name", p.displayName())
            .bind("sports", json(pr.sports())).bind("brands", json(pr.favoriteBrands())).bind("cats", json(pr.favoriteCategories()))
            .bind("teams", json(pr.favoriteTeams())).bind("sizes", json(pr.sizes())).bind("attrs", json(pr.attributes()))
            .bind("pers", p.personalizationEnabled()).bind("created", p.createdAt()).bind("updated", p.updatedAt());
        spec = p.relationship() == null ? spec.bindNull("rel", String.class) : spec.bind("rel", p.relationship());
        spec = p.ageBand() == null ? spec.bindNull("band", String.class) : spec.bind("band", p.ageBand().name());
        spec = pr.usageLevel() == null ? spec.bindNull("usage", String.class) : spec.bind("usage", pr.usageLevel().name());
        spec = pr.priceMin() == null ? spec.bindNull("pmin", BigDecimal.class) : spec.bind("pmin", pr.priceMin());
        spec = pr.priceMax() == null ? spec.bindNull("pmax", BigDecimal.class) : spec.bind("pmax", pr.priceMax());
        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Boolean> deleteFamily(UUID id, UUID ownerUserId) {
        return db.sql("DELETE FROM shopper_profiles WHERE id = :id AND owner_user_id = :o AND kind = 'FAMILY'")
            .bind("id", id).bind("o", ownerUserId).fetch().rowsUpdated().map(n -> n > 0);
    }

    @Override
    public Mono<UUID> activeProfileId(UUID ownerUserId) {
        return db.sql("SELECT profile_id FROM shopper_active_profile WHERE owner_user_id = :o")
            .bind("o", ownerUserId).map((row, meta) -> row.get("profile_id", UUID.class)).one();
    }

    @Override
    public Mono<Void> setActive(UUID ownerUserId, UUID profileId) {
        return db.sql("INSERT INTO shopper_active_profile (owner_user_id, profile_id, updated_at) VALUES (:o, :p, now()) "
                + "ON CONFLICT (owner_user_id) DO UPDATE SET profile_id = :p, updated_at = now()")
            .bind("o", ownerUserId).bind("p", profileId).fetch().rowsUpdated().then();
    }

    // ------------------------------------------------------------------------------------------------ mapping

    private static ShopperProfile map(Row row) {
        var level = row.get("usage_level", String.class);
        var prefs = new ShoppingPreferences(list(row.get("sports", String.class)), list(row.get("favorite_brands", String.class)),
            list(row.get("favorite_categories", String.class)), list(row.get("favorite_teams", String.class)),
            level == null ? null : UsageLevel.valueOf(level), row.get("price_min", BigDecimal.class), row.get("price_max", BigDecimal.class),
            map(row.get("sizes", String.class)), map(row.get("attributes", String.class)));
        var band = row.get("age_band", String.class);
        return new ShopperProfile(row.get("id", UUID.class), row.get("owner_user_id", UUID.class), ProfileKind.valueOf(row.get("kind", String.class)),
            row.get("display_name", String.class), row.get("relationship", String.class), band == null ? null : AgeBand.valueOf(band), prefs,
            Boolean.TRUE.equals(row.get("personalization_enabled", Boolean.class)), row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise profile preferences", e);
        }
    }

    private static List<String> list(String raw) {
        try {
            return raw == null ? List.of() : JSON.readValue(raw, LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored preference list is not valid JSON", e);
        }
    }

    private static Map<String, String> map(String raw) {
        try {
            return raw == null ? Map.of() : JSON.readValue(raw, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored preference map is not valid JSON", e);
        }
    }
}
