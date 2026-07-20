package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.command.*;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.*;
import com.dsports.pricing.domain.model.PriceId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/admin/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
public class AdminPriceController {

    private final CreatePriceUseCase createPriceUseCase;
    private final UpdatePriceUseCase updatePriceUseCase;
    private final ActivatePriceUseCase activatePriceUseCase;
    private final SchedulePriceUseCase schedulePriceUseCase;
    private final ArchivePriceUseCase archivePriceUseCase;
    private final GetPriceUseCase getPriceUseCase;

    public AdminPriceController(CreatePriceUseCase createPriceUseCase,
                                 UpdatePriceUseCase updatePriceUseCase,
                                 ActivatePriceUseCase activatePriceUseCase,
                                 SchedulePriceUseCase schedulePriceUseCase,
                                 ArchivePriceUseCase archivePriceUseCase,
                                 GetPriceUseCase getPriceUseCase) {
        this.createPriceUseCase = createPriceUseCase;
        this.updatePriceUseCase = updatePriceUseCase;
        this.activatePriceUseCase = activatePriceUseCase;
        this.schedulePriceUseCase = schedulePriceUseCase;
        this.archivePriceUseCase = archivePriceUseCase;
        this.getPriceUseCase = getPriceUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PriceResult> createPrice(@Valid @RequestBody CreatePriceCommand command) {
        return createPriceUseCase.execute(command);
    }

    @PutMapping("/{id}")
    public Mono<PriceResult> updatePrice(@PathVariable UUID id, @Valid @RequestBody UpdatePriceCommand command) {
        return updatePriceUseCase.execute(command);
    }

    @GetMapping("/{id}")
    public Mono<PriceResult> getPrice(@PathVariable UUID id) {
        return getPriceUseCase.execute(PriceId.fromUUID(id));
    }

    @PatchMapping("/{id}/activate")
    public Mono<PriceResult> activatePrice(@PathVariable UUID id) {
        return activatePriceUseCase.execute(new ActivatePriceCommand(id));
    }

    @PatchMapping("/{id}/schedule")
    public Mono<PriceResult> schedulePrice(@PathVariable UUID id, @Valid @RequestBody SchedulePriceCommand command) {
        return schedulePriceUseCase.execute(command);
    }

    @PatchMapping("/{id}/archive")
    public Mono<PriceResult> archivePrice(@PathVariable UUID id) {
        return archivePriceUseCase.execute(new ArchivePriceCommand(id));
    }
}
