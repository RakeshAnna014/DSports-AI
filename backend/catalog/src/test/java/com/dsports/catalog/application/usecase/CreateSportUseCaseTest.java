package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateSportCommand;
import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSportUseCaseTest {

    @Mock
    private SportRepository sportRepository;

    @Mock
    private EventPublisher eventPublisher;

    private CreateSportUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateSportUseCase(sportRepository);
    }

    @Test
    void shouldCreateSportSuccessfully() {
        var command = new CreateSportCommand("Cricket", "cricket", "A bat-and-ball sport");

        when(sportRepository.existsByName(any(SportName.class))).thenReturn(Mono.just(false));
        when(sportRepository.existsBySlug(any())).thenReturn(Mono.just(false));
        when(sportRepository.save(any(Sport.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.name()).isEqualTo("Cricket");
                    assertThat(result.slug()).isEqualTo("cricket");
                    assertThat(result.description()).isEqualTo("A bat-and-ball sport");
                    assertThat(result.status()).isEqualTo("ACTIVE");
                    assertThat(result.id()).isNotNull();
                })
                .verifyComplete();

        verify(sportRepository).save(any(Sport.class));
    }

    @Test
    void shouldRejectDuplicateName() {
        var command = new CreateSportCommand("Cricket", "cricket", null);

        when(sportRepository.existsByName(any(SportName.class))).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.DUPLICATE_SPORT_NAME);
                })
                .verify();

        verify(sportRepository, never()).save(any());
    }

    @Test
    void shouldRejectDuplicateSlug() {
        var command = new CreateSportCommand("Cricket", "cricket", null);

        when(sportRepository.existsByName(any(SportName.class))).thenReturn(Mono.just(false));
        when(sportRepository.existsBySlug(any())).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.DUPLICATE_SLUG);
                })
                .verify();

        verify(sportRepository, never()).save(any());
    }
}
