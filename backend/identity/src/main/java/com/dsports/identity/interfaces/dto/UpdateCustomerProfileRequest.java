package com.dsports.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record UpdateCustomerProfileRequest(
    @NotBlank String firstName,
    @NotBlank String lastName,
    String phoneNumber,
    String profileImageUrl,
    LocalDate dateOfBirth
) {}
