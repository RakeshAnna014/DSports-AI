package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.BrandId;

public record ArchiveBrandCommand(
    BrandId brandId
) {}
