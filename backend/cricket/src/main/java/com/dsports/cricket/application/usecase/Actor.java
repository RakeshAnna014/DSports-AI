package com.dsports.cricket.application.usecase;

import java.util.UUID;

/** Who is acting. The id always comes from the verified token; {@code admin} from the token's roles, never from the request. */
public record Actor(UUID id, boolean admin) {

    public static Actor user(UUID id) {
        return new Actor(id, false);
    }
}
