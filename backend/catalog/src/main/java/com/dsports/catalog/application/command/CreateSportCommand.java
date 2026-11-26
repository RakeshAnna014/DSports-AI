package com.dsports.catalog.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSportCommand(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(max = 150) String slug,
    String description,
    Boolean bookable
) {
    public CreateSportCommand(String name, String slug, String description) {
        this(name, slug, description, null);
    }
}
