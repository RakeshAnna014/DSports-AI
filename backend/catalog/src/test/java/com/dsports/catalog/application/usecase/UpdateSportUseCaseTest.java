package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateSportCommand;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateSportUseCaseTest {

    @Mock
    private SportRepository sportRepository;

    private UpdateSportUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateSportUseCase(sportRepository);
    }

    @Test
    void shouldUpdateSportSuccessfully() {
        var id = SportId.generate();
        var sport = Sport.create(SportName.from("Old"), Slug.from("old"), "Old desc");
        sport = Sport.reconstitute(id, sport.getName(), sport.getSlug(),
                sport.getDescription(), sport.getStatus(),
                sport.getCreatedAt(), sport.getUpdatedAt(), 0);

        var command = new UpdateSportCommand(id, "New", "new", "New desc");

        when(sportRepository.findById(id)).thenReturn(Mono.just(sport));
        when(sportRepository.existsByName(any(SportName.class))).thenReturn(Mono.just(false));
        when(sportRepository.existsBySlug(any())).thenReturn(Mono.just(false));
        when(sportRepository.save(any(Sport.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.name()).isEqualTo("New");
                    assertThat(result.slug()).isEqualTo("new");
                })
                .verifyComplete();

        verify(sportRepository).save(any(Sport.class));
    }

    @Test
    void shouldRejectWhenSportNotFound() {
        var id = SportId.generate();
        var command = new UpdateSportCommand(id, "New", "new", null);

        when(sportRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .expectError(com.dsports.catalog.domain.exception.CatalogDomainException.class)
                .verify();

        verify(sportRepository, never()).save(any());
    }
}
