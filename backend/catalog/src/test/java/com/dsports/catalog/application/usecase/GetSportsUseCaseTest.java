package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportName;
import com.dsports.catalog.domain.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetSportsUseCaseTest {

    @Mock
    private SportRepository sportRepository;

    private GetSportsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetSportsUseCase(sportRepository);
    }

    @Test
    void shouldReturnAllActiveSports() {
        var sport1 = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), null);
        var sport2 = Sport.create(SportName.from("Football"), Slug.from("football"), null);

        when(sportRepository.findAllActive()).thenReturn(Flux.just(sport1, sport2));

        StepVerifier.create(useCase.execute())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyWhenNoSports() {
        when(sportRepository.findAllActive()).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute())
                .verifyComplete();
    }
}
