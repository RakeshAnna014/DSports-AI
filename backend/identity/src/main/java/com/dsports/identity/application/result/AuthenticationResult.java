package com.dsports.identity.application.result;

import java.util.UUID;

public record AuthenticationResult(
    UUID userId,
    String email,
    boolean success,
    AuthenticationFailureReason failureReason
) {
    public static AuthenticationResult success(UUID userId, String email) {
        return new AuthenticationResult(userId, email, true, null);
    }

    public static AuthenticationResult failure(AuthenticationFailureReason reason) {
        return new AuthenticationResult(null, null, false, reason);
    }
}
