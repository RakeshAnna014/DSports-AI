package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.SportId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSportCommand(
    SportId sportId,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(max = 150) String slug,
    String description,
    Boolean bookable
) {
    public UpdateSportCommand(SportId sportId, String name, String slug, String description) {
        this(sportId, name, slug, description, null);
    }
}
