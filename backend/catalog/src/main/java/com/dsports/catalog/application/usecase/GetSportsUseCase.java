package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import reactor.core.publisher.Flux;

public class GetSportsUseCase {

    private final SportRepository sportRepository;

    public GetSportsUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    public Flux<SportResult> execute() {
        return sportRepository.findAllActive()
                .map(CreateSportUseCase::toResult);
    }
}
