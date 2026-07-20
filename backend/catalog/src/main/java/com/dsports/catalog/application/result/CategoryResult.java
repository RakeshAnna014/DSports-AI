package com.dsports.catalog.application.result;

import java.time.Instant;
import java.util.UUID;

public record CategoryResult(
    UUID id,
    String name,
    String slug,
    String description,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
