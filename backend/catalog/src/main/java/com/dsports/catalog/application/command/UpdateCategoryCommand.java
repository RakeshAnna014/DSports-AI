package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.CategoryId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCategoryCommand(
    CategoryId categoryId,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(max = 150) String slug,
    String description
) {}
