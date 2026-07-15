package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.User;

import java.time.Duration;
import java.util.List;

public interface TokenProvider {
    String generateAccessToken(User user);
    String generateRefreshToken();
    Duration getRefreshTokenExpiration();
    String extractUserId(String accessToken);
    List<String> extractRoles(String accessToken);
}
