package com.dsports.ground.interfaces;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** The caller's id always comes from the verified token, never from a request body or parameter. */
final class Principals {

    private Principals() {
    }

    static UUID callerId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
