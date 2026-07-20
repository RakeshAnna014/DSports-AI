package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.CategoryId;

public record ArchiveCategoryCommand(
    CategoryId categoryId
) {}
