package com.dsports.identity.application.command;

import com.dsports.identity.domain.model.UserId;

import java.time.LocalDate;

public record UpdateCustomerProfileCommand(
    UserId userId,
    String firstName,
    String lastName,
    String phoneNumber,
    String profileImageUrl,
    LocalDate dateOfBirth
) {}
