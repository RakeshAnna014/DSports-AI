package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveSportCommand;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import com.dsports.catalog.domain.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArchiveSportUseCaseTest {

    @Mock
    private SportRepository sportRepository;

    private ArchiveSportUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ArchiveSportUseCase(sportRepository);
    }

    @Test
    void shouldArchiveSport() {
        var id = SportId.generate();
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), null);
        sport = Sport.reconstitute(id, sport.getName(), sport.getSlug(),
                sport.getDescription(), Status.ACTIVE,
                sport.getCreatedAt(), sport.getUpdatedAt(), 0);

        when(sportRepository.findById(id)).thenReturn(Mono.just(sport));
        when(sportRepository.save(any(Sport.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchiveSportCommand(id)))
                .assertNext(result -> org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("ARCHIVED"))
                .verifyComplete();

        verify(sportRepository).save(any(Sport.class));
    }

    @Test
    void shouldRejectWhenSportNotFound() {
        var id = SportId.generate();

        when(sportRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchiveSportCommand(id)))
                .expectError(com.dsports.catalog.domain.exception.CatalogDomainException.class)
                .verify();
    }
}
