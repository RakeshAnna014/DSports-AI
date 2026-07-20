package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.SportId;
import reactor.core.publisher.Mono;

public class GetSportUseCase {

    private final SportRepository sportRepository;

    public GetSportUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    public Mono<SportResult> execute(SportId id) {
        return sportRepository.findById(id)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.SPORT_NOT_FOUND,
                        "Sport not found: " + id)))
                .map(CreateSportUseCase::toResult);
    }
}
