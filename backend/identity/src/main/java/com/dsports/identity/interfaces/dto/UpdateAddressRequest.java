package com.dsports.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UpdateAddressRequest(
    @NotBlank String line1,
    String line2,
    @NotBlank String city,
    @NotBlank String state,
    @NotBlank String country,
    @NotBlank String postalCode,
    @NotNull @Pattern(regexp = "SHIPPING|BILLING", message = "must be one of SHIPPING, BILLING") String type
) {}
