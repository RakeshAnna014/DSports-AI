package com.dsports.returns.application.usecase;

import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.returns.domain.model.ReturnRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Reads (owner-scoped) and administrative decisions on return requests. */
public class ManageReturnsUseCase {
    private static final Logger log = LoggerFactory.getLogger(ManageReturnsUseCase.class);

    private final ReturnRepository returnRepository;

    public ManageReturnsUseCase(ReturnRepository returnRepository) {
        this.returnRepository = returnRepository;
    }

    public Flux<ReturnRequest> mine(UUID userId) {
        return returnRepository.findByUserId(userId);
    }

    public Flux<ReturnRequest> all() {
        return returnRepository.findAll();
    }

    public Mono<ReturnRequest> get(UUID id, UUID userId, boolean admin) {
        return returnRepository.findById(id)
            .switchIfEmpty(Mono.error(notFound(id)))
            .flatMap(request -> admin || request.getUserId().equals(userId)
                ? Mono.just(request)
                : Mono.error(new ReturnsDomainException(ReturnsErrorCode.RETURN_NOT_OWNED_BY_USER,
                    "Return does not belong to the current user")));
    }

    public Mono<ReturnRequest> approve(UUID id, String note) {
        return decide(id, request -> request.approve(note), "approved");
    }

    public Mono<ReturnRequest> reject(UUID id, String note) {
        return decide(id, request -> request.reject(note), "rejected");
    }

    private Mono<ReturnRequest> decide(UUID id, java.util.function.Consumer<ReturnRequest> decision, String verb) {
        return returnRepository.findById(id)
            .switchIfEmpty(Mono.error(notFound(id)))
            .flatMap(request -> {
                decision.accept(request);
                return returnRepository.save(request)
                    .doOnSuccess(v -> log.info("Return {} {}", id, verb))
                    .thenReturn(request);
            });
    }

    private static ReturnsDomainException notFound(UUID id) {
        return new ReturnsDomainException(ReturnsErrorCode.RETURN_NOT_FOUND, "Return not found: " + id);
    }
}
