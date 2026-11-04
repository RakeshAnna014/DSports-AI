package com.dsports.identity.domain.profile;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every field is optional. Nothing here is required to shop; an empty preference set simply means "no personalisation signal".
 * Sports are validated against the live catalog by the use case (the catalog owns that taxonomy); this record validates shape and limits.
 */
public record ShoppingPreferences(List<String> sports, List<String> favoriteBrands, List<String> favoriteCategories, List<String> favoriteTeams,
                                  UsageLevel usageLevel, BigDecimal priceMin, BigDecimal priceMax, Map<String, String> sizes,
                                  Map<String, String> attributes) {

    public static final int MAX_SPORTS = 8;
    public static final int MAX_LIST = 10;
    public static final int MAX_TEXT = 60;
    public static final int MAX_ATTRIBUTES = 10;
    /** Size/fit keys the UI offers; anything else is rejected so the stored data stays interpretable. */
    public static final Set<String> SIZE_KEYS = Set.of("shoe", "clothing", "glove", "fit", "helmet", "racket_grip");

    public static ShoppingPreferences empty() {
        return new ShoppingPreferences(List.of(), List.of(), List.of(), List.of(), null, null, null, Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return sports.isEmpty() && favoriteBrands.isEmpty() && favoriteCategories.isEmpty() && favoriteTeams.isEmpty() && usageLevel == null
            && priceMin == null && priceMax == null && sizes.isEmpty() && attributes.isEmpty();
    }

    /** Trims, de-duplicates (case-insensitively) and bounds everything; throws {@code VALIDATION_ERROR} on anything out of range. */
    public static ShoppingPreferences normalised(List<String> sports, List<String> brands, List<String> categories, List<String> teams,
                                                 UsageLevel level, BigDecimal min, BigDecimal max, Map<String, String> sizes,
                                                 Map<String, String> attributes) {
        var s = clean(sports, MAX_SPORTS, "sports");
        var b = clean(brands, MAX_LIST, "favorite brands");
        var c = clean(categories, MAX_LIST, "favorite categories");
        var t = clean(teams, MAX_LIST, "favorite teams");
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0) {
            throw invalid("Price range cannot be negative");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid("The minimum price cannot be above the maximum price");
        }
        var sz = new LinkedHashMap<String, String>();
        if (sizes != null) {
            sizes.forEach((k, v) -> {
                if (k == null || !SIZE_KEYS.contains(k.trim().toLowerCase())) {
                    throw invalid("Unknown size/fit field '" + k + "'. Allowed: " + SIZE_KEYS);
                }
                if (v != null && !v.isBlank()) {
                    sz.put(k.trim().toLowerCase(), bounded(v, "size/fit value"));
                }
            });
        }
        var attrs = new LinkedHashMap<String, String>();
        if (attributes != null) {
            if (attributes.size() > MAX_ATTRIBUTES) {
                throw invalid("At most " + MAX_ATTRIBUTES + " attributes are allowed");
            }
            attributes.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    attrs.put(bounded(k, "attribute name").toLowerCase(), bounded(v, "attribute value"));
                }
            });
        }
        return new ShoppingPreferences(s, b, c, t, level, min, max, Map.copyOf(sz), Map.copyOf(attrs));
    }

    private static List<String> clean(List<String> values, int max, String what) {
        if (values == null) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<String>();
        for (var v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            var trimmed = bounded(v, what);
            if (seen.add(trimmed.toLowerCase())) {
                out.add(trimmed);
            }
        }
        if (out.size() > max) {
            throw invalid("At most " + max + " " + what + " are allowed");
        }
        return List.copyOf(out);
    }

    private static String bounded(String value, String what) {
        var v = value.trim();
        if (v.length() > MAX_TEXT) {
            throw invalid("A " + what + " is limited to " + MAX_TEXT + " characters");
        }
        return v;
    }

    private static IdentityDomainException invalid(String message) {
        return new IdentityDomainException(ErrorCode.VALIDATION_ERROR, message);
    }
}
