package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateSportCommand;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportName;
import reactor.core.publisher.Mono;

public class CreateSportUseCase {

    private final SportRepository sportRepository;

    public CreateSportUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    public Mono<SportResult> execute(CreateSportCommand command) {
        var name = SportName.from(command.name());
        var slug = Slug.from(command.slug());

        return sportRepository.existsByName(name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SPORT_NAME,
                                "Sport with name '" + command.name() + "' already exists"));
                    }
                    return sportRepository.existsBySlug(slug);
                })
                .flatMap(slugExists -> {
                    if (slugExists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Sport with slug '" + command.slug() + "' already exists"));
                    }
                    var sport = Sport.create(name, slug, command.description());
                    return sportRepository.save(sport)
                            .thenReturn(toResult(sport));
                });
    }

    public static SportResult toResult(Sport sport) {
        return new SportResult(
                sport.getId().value(),
                sport.getName().value(),
                sport.getSlug().value(),
                sport.getDescription(),
                sport.getStatus().name(),
                sport.getCreatedAt(),
                sport.getUpdatedAt()
        );
    }
}
