package com.dsports.catalog.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductCommand(
    @NotBlank @Size(max = 50) String sku,
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 150) String slug,
    @Size(max = 2000) String description,
    @NotNull UUID brandId,
    @NotNull UUID categoryId,
    @NotNull UUID sportId,
    BigDecimal weight,
    String weightUnit,
    BigDecimal length,
    BigDecimal width,
    BigDecimal height,
    String dimensionUnit
) {}
