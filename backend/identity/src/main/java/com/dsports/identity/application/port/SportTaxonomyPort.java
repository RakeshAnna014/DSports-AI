package com.dsports.identity.application.port;

import reactor.core.publisher.Mono;

import java.util.Set;

/** The sports the catalog actually sells, so a preference can only name a real sport. The catalog module owns the answer. */
public interface SportTaxonomyPort {

    /** Display names of the active sports. */
    Mono<Set<String>> activeSportNames();
}
