package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.OwnerGroundResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/** A ground owner creating, listing and editing their own grounds. The caller's id always comes from the verified token, never from the request. */
public class ManageGroundsUseCase {

    private final GroundRepository grounds;
    private final Clock clock;

    public ManageGroundsUseCase(GroundRepository grounds, Clock clock) {
        this.grounds = grounds;
        this.clock = clock;
    }

    public Mono<OwnerGroundResult> create(UUID ownerId, Ground.Spec spec) {
        return Mono.fromSupplier(() -> Ground.create(ownerId, spec, clock.instant()))
            .flatMap(grounds::save)
            .map(OwnerGroundResult::from);
    }

    public Flux<OwnerGroundResult> list(UUID ownerId) {
        return grounds.findByOwner(ownerId).map(OwnerGroundResult::from);
    }

    /**
     * Replaces a ground's details and active flag. {@code expectedVersion} must be the version the owner last saw: an edit made on a
     * stale copy is refused with a conflict instead of silently overwriting a newer one. Existing bookings keep their price snapshot.
     */
    public Mono<OwnerGroundResult> update(UUID ownerId, UUID groundId, Ground.Spec spec, boolean active, int expectedVersion) {
        return OwnerGrounds.owned(grounds, ownerId, groundId).flatMap(ground -> {
            if (ground.getVersion() != expectedVersion) {
                return Mono.error(new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                    "This ground was changed since you opened it. Reload and try again."));
            }
            var now = clock.instant();
            ground.update(spec, now);
            if (active) {
                ground.activate(now);
            } else {
                ground.deactivate(now);
            }
            return grounds.save(ground).map(OwnerGroundResult::from);
        });
    }
}
