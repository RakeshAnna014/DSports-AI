package com.dsports.identity.application.result;

public record RefreshTokenResult(
    String accessToken,
    String refreshToken,
    boolean success,
    RefreshFailureReason failureReason
) {
    public static RefreshTokenResult success(String accessToken, String refreshToken) {
        return new RefreshTokenResult(accessToken, refreshToken, true, null);
    }

    public static RefreshTokenResult failure(RefreshFailureReason reason) {
        return new RefreshTokenResult(null, null, false, reason);
    }
}
