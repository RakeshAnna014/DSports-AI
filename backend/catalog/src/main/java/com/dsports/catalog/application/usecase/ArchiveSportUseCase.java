package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveSportCommand;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import reactor.core.publisher.Mono;

public class ArchiveSportUseCase {

    private final SportRepository sportRepository;

    public ArchiveSportUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    public Mono<SportResult> execute(ArchiveSportCommand command) {
        return sportRepository.findById(command.sportId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.SPORT_NOT_FOUND,
                        "Sport not found: " + command.sportId())))
                .flatMap(sport -> {
                    sport.archive();
                    return sportRepository.save(sport)
                            .thenReturn(CreateSportUseCase.toResult(sport));
                });
    }
}
