package com.dsports.ground.application.port;

import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

/**
 * The catalogue's sports, seen from the ground module. The ground module does not depend on the catalogue: bootstrap implements this over it.
 * A sport can be booked only when the catalogue marks it {@code bookable} (and it is active).
 */
public interface SportCatalogPort {

    record SportRef(UUID id, String name, String slug, boolean bookable) {
    }

    /** The active sports with these ids (unknown or archived ids are simply absent). */
    Flux<SportRef> findByIds(Collection<UUID> ids);

    /** Active sports that can be booked, by name. */
    Flux<SportRef> findBookable();
}
