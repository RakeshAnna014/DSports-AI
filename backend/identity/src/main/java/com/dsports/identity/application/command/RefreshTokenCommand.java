package com.dsports.identity.application.command;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenCommand(
    @NotBlank String refreshToken
) {}
