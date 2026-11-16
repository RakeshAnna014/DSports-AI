package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.PitchType;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public class GroundR2dbcRepositoryAdapter implements GroundRepository {

    private final SpringR2dbcGroundRepository springRepository;

    public GroundR2dbcRepositoryAdapter(SpringR2dbcGroundRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Mono<Ground> findById(UUID id) {
        return springRepository.findById(id).map(GroundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Ground> findActive(String cityOrNull) {
        var entities = cityOrNull == null
            ? springRepository.findByActiveTrueOrderByNameAsc()
            : springRepository.findByActiveTrueAndCityIgnoreCaseOrderByNameAsc(cityOrNull);
        return entities.map(GroundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Ground> findAllById(java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? Flux.empty() : springRepository.findAllById(ids).map(GroundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Ground> findByOwner(UUID ownerId) {
        return springRepository.findByOwnerIdOrderByNameAsc(ownerId).map(GroundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Ground> save(Ground ground) {
        return springRepository.save(toEntity(ground))
            .doOnNext(saved -> ground.setVersion(saved.getVersion()))
            .thenReturn(ground)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Ground " + ground.getId() + " was modified concurrently. Reload and retry."));
    }

    static Ground toDomain(GroundEntity e) {
        var spec = new Ground.Spec(e.getName(), e.getAddress(), e.getCity(), e.getLatitude(), e.getLongitude(), PitchType.valueOf(e.getPitchType()),
            List.of(e.getFacilities() == null ? new String[0] : e.getFacilities()), List.of(e.getPhotoUrls() == null ? new String[0] : e.getPhotoUrls()),
            e.getOpensAt(), e.getClosesAt(), ZoneId.of(e.getTimeZone()), e.getSlotMinutes(), e.getBasePrice(), e.getWeekendPrice(), e.getPeakPrice(),
            e.getPeakFrom(), e.getPeakTo());
        return Ground.reconstitute(e.getId(), e.getOwnerId(), spec, e.getCurrency(), e.isActive(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static GroundEntity toEntity(Ground g) {
        var s = g.getSpec();
        var e = new GroundEntity();
        e.setId(g.getId());
        e.setOwnerId(g.getOwnerId());
        e.setName(s.name());
        e.setAddress(s.address());
        e.setCity(s.city());
        e.setLatitude(s.latitude());
        e.setLongitude(s.longitude());
        e.setPitchType(s.pitchType().name());
        e.setFacilities(s.facilities().toArray(String[]::new));
        e.setPhotoUrls(s.photoUrls().toArray(String[]::new));
        e.setOpensAt(s.opensAt());
        e.setClosesAt(s.closesAt());
        e.setTimeZone(s.timeZone().getId());
        e.setSlotMinutes(s.slotMinutes());
        e.setBasePrice(s.basePrice());
        e.setWeekendPrice(s.weekendPrice());
        e.setPeakPrice(s.peakPrice());
        e.setPeakFrom(s.peakFrom());
        e.setPeakTo(s.peakTo());
        e.setCurrency(g.getCurrency());
        e.setActive(g.isActive());
        e.setVersion(g.getVersion());
        e.setCreatedAt(g.getCreatedAt());
        e.setUpdatedAt(g.getUpdatedAt());
        return e;
    }
}
