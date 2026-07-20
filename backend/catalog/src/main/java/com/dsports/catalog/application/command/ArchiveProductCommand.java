package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.ProductId;

public record ArchiveProductCommand(
    ProductId productId
) {}
