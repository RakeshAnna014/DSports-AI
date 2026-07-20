package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateSportCommand;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.SportName;
import reactor.core.publisher.Mono;

public class UpdateSportUseCase {

    private final SportRepository sportRepository;

    public UpdateSportUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    public Mono<SportResult> execute(UpdateSportCommand command) {
        return sportRepository.findById(command.sportId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.SPORT_NOT_FOUND,
                        "Sport not found: " + command.sportId())))
                .flatMap(sport -> {
                    var newName = SportName.from(command.name());
                    var newSlug = Slug.from(command.slug());

                    var nameCheck = sport.getName().value().equals(command.name())
                            ? Mono.just(false)
                            : sportRepository.existsByName(newName);

                    var slugCheck = sport.getSlug().value().equals(command.slug())
                            ? Mono.just(false)
                            : sportRepository.existsBySlug(newSlug);

                    return Mono.zip(nameCheck, slugCheck)
                            .flatMap(tuple -> {
                                if (tuple.getT1()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SPORT_NAME,
                                            "Sport with name '" + command.name() + "' already exists"));
                                }
                                if (tuple.getT2()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                            "Sport with slug '" + command.slug() + "' already exists"));
                                }
                                try {
                                    sport.update(newName, newSlug, command.description());
                                } catch (IllegalStateException e) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                            "Cannot update an archived sport"));
                                }
                                return sportRepository.save(sport)
                                        .thenReturn(CreateSportUseCase.toResult(sport));
                            });
                });
    }
}
