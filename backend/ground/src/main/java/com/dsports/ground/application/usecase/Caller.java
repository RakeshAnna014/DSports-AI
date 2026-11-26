package com.dsports.ground.application.usecase;

import java.util.UUID;

/**
 * Who is managing venues: a venue owner (their own venues only) or an administrator (any venue, on an owner's behalf). The id always comes
 * from the verified token, and {@code admin} from the route the request arrived on, never from the request body.
 */
public record Caller(UUID id, boolean admin) {

    public static Caller owner(UUID id) {
        return new Caller(id, false);
    }

    public static Caller administrator(UUID id) {
        return new Caller(id, true);
    }
}
