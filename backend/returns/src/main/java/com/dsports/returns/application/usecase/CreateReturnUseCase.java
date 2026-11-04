package com.dsports.returns.application.usecase;

import com.dsports.returns.application.port.ReturnOrderPort;
import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.returns.domain.model.ReturnRequest;
import com.dsports.returns.domain.policy.ReturnPolicy;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Creates a return request after re-checking eligibility inside the transaction. Idempotent per
 * (customer, Idempotency-Key): a retry returns the original request, while reusing the key for a different
 * request is rejected. Concurrent requests for the same order line are serialised by an advisory lock, so the
 * returnable quantity can never be exceeded.
 */
public class CreateReturnUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateReturnUseCase.class);

    private final ReturnOrderPort orderPort;
    private final ReturnRepository returnRepository;
    private final ReturnPolicy policy;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public CreateReturnUseCase(ReturnOrderPort orderPort, ReturnRepository returnRepository, ReturnPolicy policy,
                               TransactionRunner transactionRunner, Clock clock) {
        this.orderPort = orderPort;
        this.returnRepository = returnRepository;
        this.policy = policy;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public record Command(UUID userId, UUID orderId, UUID productId, int quantity, String reason, String idempotencyKey) {}

    public Mono<ReturnRequest> execute(Command command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return Mono.error(new ReturnsDomainException(ReturnsErrorCode.VALIDATION_ERROR,
                "An Idempotency-Key header is required to create a return"));
        }
        return transactionRunner.inTransaction(() -> returnRepository
            .lockOrderLine(command.orderId(), command.productId())
            .then(Mono.defer(() -> returnRepository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey())))
            .flatMap(existing -> sameRequest(existing, command)
                ? Mono.just(existing)
                : Mono.<ReturnRequest>error(new ReturnsDomainException(ReturnsErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different return request")))
            .switchIfEmpty(Mono.defer(() -> create(command))));
    }

    private Mono<ReturnRequest> create(Command command) {
        return orderPort.findOrderOfCustomer(command.orderId(), command.userId())
            .flatMap(order -> returnRepository.sumHeldQuantity(command.orderId(), command.productId())
                .map(held -> policy.evaluate(command.orderId(), command.productId(), order, held, clock.instant())))
            .switchIfEmpty(Mono.fromSupplier(() ->
                policy.evaluate(command.orderId(), command.productId(), null, 0, clock.instant())))
            .flatMap(eligibility -> {
                if (!eligibility.eligible()) {
                    return Mono.error(new ReturnsDomainException(ReturnsErrorCode.NOT_ELIGIBLE,
                        eligibility.reason() + ": " + eligibility.message()));
                }
                if (command.quantity() < 1 || command.quantity() > eligibility.maxReturnableQuantity()) {
                    return Mono.error(new ReturnsDomainException(ReturnsErrorCode.NOT_ELIGIBLE,
                        "Quantity must be between 1 and " + eligibility.maxReturnableQuantity()));
                }
                var request = ReturnRequest.create(command.orderId(), command.userId(), command.productId(),
                    eligibility.productName(), command.quantity(), command.reason(), command.idempotencyKey());
                return returnRepository.save(request)
                    .doOnSuccess(v -> log.info("Return {} requested: {} x product {} of order {}", request.getId(),
                        request.getQuantity(), request.getProductId(), request.getOrderId()))
                    .thenReturn(request);
            });
    }

    private static boolean sameRequest(ReturnRequest existing, Command command) {
        return existing.getOrderId().equals(command.orderId())
            && existing.getProductId().equals(command.productId())
            && existing.getQuantity() == command.quantity();
    }
}
