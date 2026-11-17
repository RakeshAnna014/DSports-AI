package com.dsports.cricket.application.port;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Who a customer id is, for showing a claimant to the person who must decide the claim. Implemented in bootstrap over the identity data. */
public interface CustomerDirectoryPort {

    record Person(String name, String email) {
    }

    Mono<Map<UUID, Person>> describe(Collection<UUID> customerIds);

    /** The registered customer with this email (ignoring case), or empty. */
    Mono<UUID> findIdByEmail(String email);
}
