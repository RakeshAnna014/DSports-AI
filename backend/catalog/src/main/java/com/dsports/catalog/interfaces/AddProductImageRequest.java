package com.dsports.catalog.interfaces;

import jakarta.validation.constraints.NotBlank;

public record AddProductImageRequest(
    @NotBlank String url,
    int displayOrder,
    boolean primary
) {}
