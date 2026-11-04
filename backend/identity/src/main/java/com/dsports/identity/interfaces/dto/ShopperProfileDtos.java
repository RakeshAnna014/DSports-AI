package com.dsports.identity.interfaces.dto;

import com.dsports.identity.application.usecase.ShopperProfileUseCase;
import com.dsports.identity.domain.profile.ShopperProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shapes of the shopper profile API. The owner is never part of a request: it is the authenticated account. */
public final class ShopperProfileDtos {

    private ShopperProfileDtos() {}

    public record PreferencesRequest(@Size(max = 8) List<@Size(max = 60) String> sports, @Size(max = 10) List<@Size(max = 60) String> favoriteBrands,
                                     @Size(max = 10) List<@Size(max = 60) String> favoriteCategories, @Size(max = 10) List<@Size(max = 60) String> favoriteTeams,
                                     @Size(max = 15) String usageLevel, BigDecimal priceMin, BigDecimal priceMax, Map<String, String> sizes,
                                     Map<String, String> attributes, Boolean personalizationEnabled) {}

    public record FamilyRequest(@NotBlank @Size(max = 60) String name, @Size(max = 30) String relationship, @NotNull @Size(max = 12) String ageBand,
                                PreferencesRequest preferences) {}

    public record SelectRequest(@NotNull UUID profileId) {}

    public record Preferences(List<String> sports, List<String> favoriteBrands, List<String> favoriteCategories, List<String> favoriteTeams,
                              String usageLevel, BigDecimal priceMin, BigDecimal priceMax, Map<String, String> sizes, Map<String, String> attributes,
                              boolean empty) {}

    public record Profile(UUID id, String kind, String displayName, String relationship, String ageBand, boolean personalizationEnabled,
                          Preferences preferences, Instant createdAt, Instant updatedAt) {}

    public record Overview(UUID activeProfileId, List<Profile> profiles, int maxFamilyProfiles) {}

    public static Profile profile(ShopperProfile p) {
        var x = p.preferences();
        return new Profile(p.id(), p.kind().name(), p.displayName(), p.relationship(), p.ageBand() == null ? null : p.ageBand().name(),
            p.personalizationEnabled(), new Preferences(x.sports(), x.favoriteBrands(), x.favoriteCategories(), x.favoriteTeams(),
                x.usageLevel() == null ? null : x.usageLevel().name(), x.priceMin(), x.priceMax(), x.sizes(), x.attributes(), x.isEmpty()),
            p.createdAt(), p.updatedAt());
    }

    public static Overview overview(ShopperProfileUseCase.Overview o) {
        return new Overview(o.activeProfileId(), o.profiles().stream().map(ShopperProfileDtos::profile).toList(), ShopperProfileUseCase.MAX_FAMILY_PROFILES);
    }
}
