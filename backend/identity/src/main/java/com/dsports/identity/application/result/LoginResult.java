package com.dsports.identity.application.result;

import java.util.List;
import java.util.UUID;

public record LoginResult(
    UUID userId,
    String email,
    List<String> roles,
    String accessToken,
    String refreshToken,
    boolean success,
    AuthenticationFailureReason failureReason
) {
    public static LoginResult success(UUID userId, String email, List<String> roles,
                                       String accessToken, String refreshToken) {
        return new LoginResult(userId, email, roles, accessToken, refreshToken, true, null);
    }

    public static LoginResult failure(AuthenticationFailureReason reason) {
        return new LoginResult(null, null, List.of(), null, null, false, reason);
    }
}
