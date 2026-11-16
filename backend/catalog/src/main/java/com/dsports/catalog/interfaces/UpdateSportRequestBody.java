package com.dsports.catalog.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSportRequestBody(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(max = 150) String slug,
    String description,
    Boolean bookable
) {
    public UpdateSportRequestBody(String name, String slug, String description) {
        this(name, slug, description, null);
    }
}
