package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.SportResult;
import reactor.core.publisher.Flux;

public class GetSportsUseCase {

    private final SportRepository sportRepository;

    public GetSportsUseCase(SportRepository sportRepository) {
        this.sportRepository = sportRepository;
    }

    /** The shop's sports: bookable-only sports without products are left out. */
    public Flux<SportResult> execute() {
        return sportRepository.findAllShoppable()
                .map(CreateSportUseCase::toResult);
    }

    /** The sports that can be booked (court and ground booking). */
    public Flux<SportResult> bookable() {
        return sportRepository.findAllBookable()
                .map(CreateSportUseCase::toResult);
    }
}
