package com.dsports.identity.application.port;

public interface TokenHasher {
    String hash(String rawToken);
}
