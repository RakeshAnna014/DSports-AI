package com.dsports.identity.interfaces;

import com.dsports.identity.application.usecase.ShopperProfileUseCase;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.profile.AgeBand;
import com.dsports.identity.domain.profile.ShoppingPreferences;
import com.dsports.identity.domain.profile.UsageLevel;
import com.dsports.identity.interfaces.dto.ShopperProfileDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Shopping preferences and family profiles of the authenticated account. The account is always the token's subject; a profile id that
 * belongs to somebody else answers 404 exactly like one that does not exist.
 */
@RestController
@RequestMapping(path = "/api/customers/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Shopper Profiles", description = "Optional preferences, family profiles and the active profile used for personalisation")
public class ShopperProfileController {

    private final ShopperProfileUseCase profiles;

    public ShopperProfileController(ShopperProfileUseCase profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profiles")
    @Operation(summary = "The account's profiles (own + family) and which one is active")
    public Mono<ShopperProfileDtos.Overview> overview(Authentication a) {
        return profiles.overview(owner(a)).map(ShopperProfileDtos::overview);
    }

    @GetMapping("/profiles/active")
    @Operation(summary = "The active profile with its preferences", description = "Used for personalisation. Falls back to the account owner's own profile.")
    public Mono<ShopperProfileDtos.Profile> active(Authentication a) {
        return profiles.active(owner(a)).map(ShopperProfileDtos::profile);
    }

    @PutMapping(path = "/profiles/active", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Switch the active profile")
    public Mono<ShopperProfileDtos.Profile> select(@Valid @RequestBody ShopperProfileDtos.SelectRequest r, Authentication a) {
        return profiles.select(owner(a), r.profileId()).map(ShopperProfileDtos::profile);
    }

    @GetMapping("/profiles/{id}")
    @Operation(summary = "One profile with its preferences")
    public Mono<ShopperProfileDtos.Profile> get(@PathVariable UUID id, Authentication a) {
        return profiles.get(owner(a), id).map(ShopperProfileDtos::profile);
    }

    @PutMapping(path = "/profiles/{id}/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace a profile's preferences", description = "Every field is optional. Sports must be sports the catalog sells.")
    public Mono<ShopperProfileDtos.Profile> updatePreferences(@PathVariable UUID id, @Valid @RequestBody ShopperProfileDtos.PreferencesRequest r, Authentication a) {
        return profiles.updatePreferences(owner(a), id, preferences(r), r.personalizationEnabled()).map(ShopperProfileDtos::profile);
    }

    @DeleteMapping("/profiles/{id}/preferences")
    @Operation(summary = "Reset a profile's preferences", description = "Clears every preference; the profile itself stays.")
    public Mono<ShopperProfileDtos.Profile> reset(@PathVariable UUID id, Authentication a) {
        return profiles.resetPreferences(owner(a), id).map(ShopperProfileDtos::profile);
    }

    @PostMapping(path = "/family-profiles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a family profile", description = "Managed by the account holder as guardian: no login, an age band instead of a date of birth.")
    public Mono<ShopperProfileDtos.Profile> createFamily(@Valid @RequestBody ShopperProfileDtos.FamilyRequest r, Authentication a) {
        return profiles.createFamily(owner(a), r.name(), r.relationship(), ageBand(r.ageBand()), r.preferences() == null ? ShoppingPreferences.empty() : preferences(r.preferences()),
            r.preferences() == null || r.preferences().personalizationEnabled() == null || r.preferences().personalizationEnabled()).map(ShopperProfileDtos::profile);
    }

    @PutMapping(path = "/family-profiles/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename / change the age band of a family profile")
    public Mono<ShopperProfileDtos.Profile> updateFamily(@PathVariable UUID id, @Valid @RequestBody ShopperProfileDtos.FamilyRequest r, Authentication a) {
        return profiles.updateFamily(owner(a), id, r.name(), r.relationship(), ageBand(r.ageBand())).map(ShopperProfileDtos::profile);
    }

    @DeleteMapping("/family-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a family profile and all preferences stored for it")
    public Mono<Void> deleteFamily(@PathVariable UUID id, Authentication a) {
        return profiles.deleteFamily(owner(a), id);
    }

    // ------------------------------------------------------------------------------------------------ mapping

    private static ShoppingPreferences preferences(ShopperProfileDtos.PreferencesRequest r) {
        UsageLevel level = null;
        if (r.usageLevel() != null && !r.usageLevel().isBlank()) {
            try {
                level = UsageLevel.valueOf(r.usageLevel().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "Unknown usage level. Allowed: " + java.util.Arrays.toString(UsageLevel.values()));
            }
        }
        return ShoppingPreferences.normalised(r.sports(), r.favoriteBrands(), r.favoriteCategories(), r.favoriteTeams(), level, r.priceMin(), r.priceMax(),
            r.sizes(), r.attributes());
    }

    private static AgeBand ageBand(String raw) {
        try {
            return AgeBand.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "Unknown age band. Allowed: " + java.util.Arrays.toString(AgeBand.values()));
        }
    }

    private static UUID owner(Authentication a) {
        return UUID.fromString(a.getPrincipal().toString());
    }
}
