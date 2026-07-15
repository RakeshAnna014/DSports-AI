package com.dsports.identity.interfaces.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
    UUID userId,
    String email,
    List<String> roles,
    String accessToken,
    String refreshToken
) {}
