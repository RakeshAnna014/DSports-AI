package com.dsports.identity.application.command;

import com.dsports.identity.domain.model.UserId;
import jakarta.validation.constraints.NotBlank;

public record LogoutCommand(
    @NotBlank String refreshToken,
    UserId userId
) {}
