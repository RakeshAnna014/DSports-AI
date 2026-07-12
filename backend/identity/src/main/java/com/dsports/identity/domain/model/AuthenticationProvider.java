package com.dsports.identity.domain.model;

public enum AuthenticationProvider {

    EMAIL,
    GOOGLE,
    APPLE,
    MICROSOFT,
    FACEBOOK;

    public boolean isOAuth() {
        return this != EMAIL;
    }

    public boolean requiresExternalVerification() {
        return isOAuth();
    }
}
