package com.dsports.identity.application.result;

import com.dsports.identity.domain.model.UserId;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerProfileResult(
    UUID userId,
    String email,
    String firstName,
    String lastName,
    String phoneNumber,
    String profileImageUrl,
    LocalDate dateOfBirth,
    List<String> roles
) {}
