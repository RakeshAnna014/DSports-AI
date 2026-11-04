package com.dsports.review.application.usecase;

import com.dsports.review.application.command.CreateReviewCommand;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.OrderQueryService;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.domain.model.Review;
import com.dsports.review.domain.model.ReviewId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateReviewUseCaseTest {

    private ReviewRepository reviewRepository;
    private OrderQueryService orderQueryService;
    private EventPublisher eventPublisher;
    private CreateReviewUseCase useCase;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        orderQueryService = mock(OrderQueryService.class);
        eventPublisher = mock(EventPublisher.class);
        useCase = new CreateReviewUseCase(reviewRepository, orderQueryService, eventPublisher);
    }

    @Test
    void shouldCreateReviewForDeliveredPurchase() {
        var productId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var command = new CreateReviewCommand(productId, orderId, userId, 5, "Great", "Nice product");

        when(orderQueryService.verifyPurchase(orderId, userId, productId))
                .thenReturn(Mono.just(OrderQueryService.PurchaseVerification.ELIGIBLE));
        when(reviewRepository.existsByProductIdAndUserId(productId, userId)).thenReturn(Mono.just(false));
        when(reviewRepository.save(any(Review.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.rating()).isEqualTo(5);
                    org.assertj.core.api.Assertions.assertThat(result.productId()).isEqualTo(productId);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectProductNotInOrder() {
        var command = command();
        when(orderQueryService.verifyPurchase(command.orderId(), command.userId(), command.productId()))
                .thenReturn(Mono.just(OrderQueryService.PurchaseVerification.PRODUCT_NOT_IN_ORDER));

        StepVerifier.create(useCase.execute(command))
                .expectErrorMatches(e -> isCode(e, ReviewErrorCode.PRODUCT_NOT_PURCHASED))
                .verify();
    }

    @Test
    void shouldRejectOrderOfAnotherCustomerOrMissingOrder() {
        var command = command();
        when(orderQueryService.verifyPurchase(command.orderId(), command.userId(), command.productId()))
                .thenReturn(Mono.just(OrderQueryService.PurchaseVerification.ORDER_NOT_FOUND));

        StepVerifier.create(useCase.execute(command))
                .expectErrorMatches(e -> isCode(e, ReviewErrorCode.ORDER_NOT_FOUND))
                .verify();
    }

    @Test
    void shouldRejectUndeliveredOrder() {
        var command = command();
        when(orderQueryService.verifyPurchase(command.orderId(), command.userId(), command.productId()))
                .thenReturn(Mono.just(OrderQueryService.PurchaseVerification.NOT_DELIVERED));

        StepVerifier.create(useCase.execute(command))
                .expectErrorMatches(e -> isCode(e, ReviewErrorCode.ORDER_NOT_DELIVERED))
                .verify();
    }

    @Test
    void shouldRejectDuplicateReview() {
        var command = command();
        when(orderQueryService.verifyPurchase(command.orderId(), command.userId(), command.productId()))
                .thenReturn(Mono.just(OrderQueryService.PurchaseVerification.ELIGIBLE));
        when(reviewRepository.existsByProductIdAndUserId(command.productId(), command.userId())).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorMatches(e -> isCode(e, ReviewErrorCode.DUPLICATE_REVIEW))
                .verify();
    }

    private static CreateReviewCommand command() {
        return new CreateReviewCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, "Great", null);
    }

    private static boolean isCode(Throwable e, ReviewErrorCode code) {
        return e instanceof com.dsports.review.domain.exception.ReviewDomainException rde && rde.getErrorCode() == code;
    }
}
