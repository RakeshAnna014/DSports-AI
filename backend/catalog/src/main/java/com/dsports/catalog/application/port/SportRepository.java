package com.dsports.catalog.application.port;

import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SportRepository {
    Mono<Sport> findById(SportId id);
    Mono<Sport> findByName(SportName name);
    Mono<Boolean> existsByName(SportName name);
    Mono<Boolean> existsBySlug(Slug slug);
    Flux<Sport> findAllActive();
    Flux<Sport> findAll();
    Mono<Void> save(Sport sport);
}
