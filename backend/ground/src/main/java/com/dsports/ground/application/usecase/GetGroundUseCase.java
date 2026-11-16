package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.GroundResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** A single active ground, for everyone. An inactive ground looks exactly like a missing one. */
public class GetGroundUseCase {

    private final GroundRepository grounds;

    public GetGroundUseCase(GroundRepository grounds) {
        this.grounds = grounds;
    }

    public Mono<GroundResult> execute(UUID groundId) {
        return grounds.findById(groundId)
            .filter(g -> g.isActive())
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.GROUND_NOT_FOUND, "Ground not found: " + groundId)))
            .map(GroundResult::from);
    }
}
