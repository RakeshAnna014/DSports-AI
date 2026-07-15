package com.dsports.identity.interfaces.dto;

public record RefreshResponse(
    String accessToken,
    String refreshToken
) {}
