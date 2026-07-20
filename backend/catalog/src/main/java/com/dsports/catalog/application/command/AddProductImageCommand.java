package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.ProductId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddProductImageCommand(
    @NotNull ProductId productId,
    @NotBlank String url,
    int displayOrder,
    boolean primary
) {}
