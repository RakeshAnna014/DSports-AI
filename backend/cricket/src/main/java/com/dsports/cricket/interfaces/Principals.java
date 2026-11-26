package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.usecase.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** The actor always comes from the verified token, never from a request body or parameter. */
final class Principals {

    private Principals() {
    }

    static Actor actor(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new Actor(UUID.fromString(authentication.getPrincipal().toString()), admin);
    }
}
