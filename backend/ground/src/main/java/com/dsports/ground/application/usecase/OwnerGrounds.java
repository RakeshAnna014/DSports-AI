package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The ownership rule shared by every owner use case: an owner can only touch their own grounds. */
final class OwnerGrounds {

    private OwnerGrounds() {
    }

    /** Loads a ground for its owner. A missing ground is 404; a ground that belongs to someone else is 403. */
    static Mono<Ground> owned(GroundRepository grounds, UUID ownerId, UUID groundId) {
        return grounds.findById(groundId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.GROUND_NOT_FOUND, "Ground not found: " + groundId)))
            .flatMap(ground -> ground.getOwnerId().equals(ownerId)
                ? Mono.just(ground)
                : Mono.error(new GroundDomainException(GroundErrorCode.GROUND_NOT_OWNED_BY_USER, "This ground does not belong to you")));
    }
}
