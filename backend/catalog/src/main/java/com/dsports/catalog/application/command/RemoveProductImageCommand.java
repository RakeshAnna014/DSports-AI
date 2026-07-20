package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductImageId;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RemoveProductImageCommand(
    @NotNull ProductId productId,
    @NotNull ProductImageId imageId
) {}
