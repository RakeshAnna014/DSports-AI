package com.dsports.identity.application.result;

import java.util.UUID;

public record RegisterUserResult(
    UUID userId,
    String email
) {}
