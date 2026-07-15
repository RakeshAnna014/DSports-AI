package com.dsports.identity.interfaces.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerProfileResponse(
    UUID userId,
    String email,
    String firstName,
    String lastName,
    String phoneNumber,
    String profileImageUrl,
    LocalDate dateOfBirth,
    List<String> roles
) {}
