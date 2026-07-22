package com.dsports.identity.interfaces.dto;

import java.util.UUID;

public record RegisterResponse(
    UUID userId,
    String email
) {}
