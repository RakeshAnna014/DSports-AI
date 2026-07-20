package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductImageId;
import jakarta.validation.constraints.NotNull;

public record ChangePrimaryImageCommand(
    @NotNull ProductId productId,
    @NotNull ProductImageId imageId
) {}
