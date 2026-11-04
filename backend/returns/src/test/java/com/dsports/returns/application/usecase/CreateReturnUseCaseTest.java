package com.dsports.returns.application.usecase;

import com.dsports.returns.application.port.ReturnOrderPort;
import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.returns.domain.model.ReturnRequest;
import com.dsports.returns.domain.policy.ReturnPolicy;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderFacts;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderItemFacts;
import com.dsports.shared.application.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateReturnUseCaseTest {

    private static final Instant DELIVERED = Instant.parse("2026-01-10T10:00:00Z");

    @Mock
    private ReturnOrderPort orderPort;
    @Mock
    private ReturnRepository repository;

    private final TransactionRunner transactions = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return work.get();
        }
    };

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private CreateReturnUseCase useCase;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(DELIVERED.plus(Duration.ofDays(2)), ZoneOffset.UTC);
        useCase = new CreateReturnUseCase(orderPort, repository, new ReturnPolicy(Duration.ofDays(7)), transactions, clock);
    }

    private OrderFacts deliveredOrder() {
        return new OrderFacts(orderId, true, "DELIVERED", DELIVERED, "INR",
            Map.of(productId, new OrderItemFacts("Bat", 2, new BigDecimal("1500.00"))));
    }

    private CreateReturnUseCase.Command command(int quantity, String key) {
        return new CreateReturnUseCase.Command(userId, orderId, productId, quantity, "Damaged on arrival", key);
    }

    private void noExistingRequest() {
        when(repository.lockOrderLine(orderId, productId)).thenReturn(Mono.empty());
        when(repository.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void createsRequestWhenEligible() {
        noExistingRequest();
        when(orderPort.findOrderOfCustomer(orderId, userId)).thenReturn(Mono.just(deliveredOrder()));
        when(repository.sumHeldQuantity(orderId, productId)).thenReturn(Mono.just(0));
        when(repository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command(2, "key-1")))
            .expectNextMatches(r -> r.getQuantity() == 2 && r.getUserId().equals(userId)
                && r.getProductName().equals("Bat"))
            .verifyComplete();
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        StepVerifier.create(useCase.execute(command(1, " ")))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.VALIDATION_ERROR)
            .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void retryWithSameKeyReturnsOriginalWithoutSaving() {
        var original = ReturnRequest.create(orderId, userId, productId, "Bat", 1, "Damaged", "key-1");
        when(repository.lockOrderLine(orderId, productId)).thenReturn(Mono.empty());
        when(repository.findByUserIdAndIdempotencyKey(userId, "key-1")).thenReturn(Mono.just(original));

        StepVerifier.create(useCase.execute(command(1, "key-1")))
            .expectNext(original)
            .verifyComplete();
        verify(repository, never()).save(any());
    }

    @Test
    void reusingKeyForDifferentRequestIsRejected() {
        var original = ReturnRequest.create(orderId, userId, productId, "Bat", 1, "Damaged", "key-1");
        when(repository.lockOrderLine(orderId, productId)).thenReturn(Mono.empty());
        when(repository.findByUserIdAndIdempotencyKey(userId, "key-1")).thenReturn(Mono.just(original));

        StepVerifier.create(useCase.execute(command(2, "key-1")))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.IDEMPOTENCY_KEY_REUSED)
            .verify();
    }

    @Test
    void quantityAboveRemainingIsRejected() {
        noExistingRequest();
        when(orderPort.findOrderOfCustomer(orderId, userId)).thenReturn(Mono.just(deliveredOrder()));
        when(repository.sumHeldQuantity(orderId, productId)).thenReturn(Mono.just(1));

        StepVerifier.create(useCase.execute(command(2, "key-2")))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.NOT_ELIGIBLE)
            .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void someoneElsesOrderLooksLikeUnknownOrder() {
        noExistingRequest();
        when(orderPort.findOrderOfCustomer(orderId, userId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command(1, "key-3")))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.NOT_ELIGIBLE && d.getMessage().contains("ORDER_NOT_FOUND"))
            .verify();
        verify(repository, never()).save(any());
    }
}
