package com.dsports.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAddressRequest(
    @NotBlank String line1,
    String line2,
    @NotBlank String city,
    @NotBlank String state,
    @NotBlank String country,
    @NotBlank String postalCode,
    @NotNull String type
) {}
