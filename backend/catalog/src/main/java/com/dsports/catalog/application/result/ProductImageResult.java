package com.dsports.catalog.application.result;

import java.util.UUID;

public record ProductImageResult(
    UUID id,
    String url,
    int displayOrder,
    boolean primary
) {}
