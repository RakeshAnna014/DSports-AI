package com.dsports.catalog.application.port;

import com.dsports.catalog.domain.model.Brand;
import com.dsports.catalog.domain.model.BrandId;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BrandRepository {
    Mono<Brand> findById(BrandId id);
    Mono<Brand> findByName(BrandName name);
    Mono<Boolean> existsByName(BrandName name);
    Mono<Boolean> existsBySlug(Slug slug);
    Flux<Brand> findAllActive();
    Flux<Brand> findAll();
    Mono<Void> save(Brand brand);
}
