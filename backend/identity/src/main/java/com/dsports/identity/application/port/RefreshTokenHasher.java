package com.dsports.identity.application.port;

public interface RefreshTokenHasher {
    String hash(String rawToken);
}
