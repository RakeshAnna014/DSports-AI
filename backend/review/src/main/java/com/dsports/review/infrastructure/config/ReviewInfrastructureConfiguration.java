package com.dsports.review.infrastructure.config;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.OrderQueryService;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.usecase.ApproveReviewUseCase;
import com.dsports.review.application.usecase.CreateReviewUseCase;
import com.dsports.review.application.usecase.GetProductReviewsUseCase;
import com.dsports.review.application.usecase.GetRatingSummaryUseCase;
import com.dsports.review.application.usecase.GetReviewEligibilityUseCase;
import com.dsports.review.application.usecase.GetReviewUseCase;
import com.dsports.review.application.usecase.HelpfulVoteUseCase;
import com.dsports.review.application.usecase.UpdateReviewUseCase;
import com.dsports.review.infrastructure.event.ReviewSpringEventPublisherAdapter;
import com.dsports.review.infrastructure.persistence.repository.ReviewR2dbcRepositoryAdapter;
import com.dsports.review.infrastructure.persistence.repository.SpringR2dbcHelpfulVoteRepository;
import com.dsports.review.infrastructure.persistence.repository.SpringR2dbcReviewRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class ReviewInfrastructureConfiguration {

    @Bean
    public EventPublisher reviewEventPublisher(ApplicationEventPublisher springPublisher) {
        return new ReviewSpringEventPublisherAdapter(springPublisher);
    }

    @Bean
    public ReviewRepository reviewRepository(SpringR2dbcReviewRepository springReviewRepo,
                                              SpringR2dbcHelpfulVoteRepository springHelpfulVoteRepo,
                                              EventPublisher eventPublisher,
                                              ReactiveTransactionManager transactionManager) {
        return new ReviewR2dbcRepositoryAdapter(springReviewRepo, springHelpfulVoteRepo, eventPublisher, transactionManager);
    }

    @Bean
    public OrderQueryService reviewOrderQueryService(OrderRepository orderRepository) {
        return new OrderQueryService() {
            @Override
            public Mono<PurchaseVerification> verifyPurchase(UUID orderId, UUID userId, UUID productId) {
                return orderRepository.findById(OrderId.fromUUID(orderId))
                        .map(order -> {
                            if (!order.getUserId().equals(userId)) {
                                return PurchaseVerification.ORDER_NOT_FOUND;
                            }
                            if (!containsProduct(order, productId)) {
                                return PurchaseVerification.PRODUCT_NOT_IN_ORDER;
                            }
                            return order.getStatus() == OrderStatus.DELIVERED
                                    ? PurchaseVerification.ELIGIBLE
                                    : PurchaseVerification.NOT_DELIVERED;
                        })
                        .defaultIfEmpty(PurchaseVerification.ORDER_NOT_FOUND);
            }

            @Override
            public Mono<UUID> findReviewableOrder(UUID userId, UUID productId) {
                return orderRepository.findByUserId(userId)
                        .filter(order -> order.getStatus() == OrderStatus.DELIVERED && containsProduct(order, productId))
                        .map(order -> order.getId().value())
                        .next();
            }
        };
    }

    private static boolean containsProduct(Order order, UUID productId) {
        return order.getItems().stream().anyMatch(item -> item.getProductId().equals(productId));
    }

    @Bean
    public CreateReviewUseCase createReviewUseCase(ReviewRepository reviewRepository,
                                                     OrderQueryService orderQueryService,
                                                     EventPublisher eventPublisher) {
        return new CreateReviewUseCase(reviewRepository, orderQueryService, eventPublisher);
    }

    @Bean
    public UpdateReviewUseCase updateReviewUseCase(ReviewRepository reviewRepository,
                                                     EventPublisher eventPublisher) {
        return new UpdateReviewUseCase(reviewRepository, eventPublisher);
    }

    @Bean
    public ApproveReviewUseCase approveReviewUseCase(ReviewRepository reviewRepository,
                                                       EventPublisher eventPublisher) {
        return new ApproveReviewUseCase(reviewRepository, eventPublisher);
    }

    @Bean
    public GetReviewEligibilityUseCase getReviewEligibilityUseCase(ReviewRepository reviewRepository,
                                                                   OrderQueryService reviewOrderQueryService) {
        return new GetReviewEligibilityUseCase(reviewRepository, reviewOrderQueryService);
    }

    @Bean
    public GetReviewUseCase getReviewUseCase(ReviewRepository reviewRepository) {
        return new GetReviewUseCase(reviewRepository);
    }

    @Bean
    public GetProductReviewsUseCase getProductReviewsUseCase(ReviewRepository reviewRepository) {
        return new GetProductReviewsUseCase(reviewRepository);
    }

    @Bean
    public GetRatingSummaryUseCase getRatingSummaryUseCase(ReviewRepository reviewRepository) {
        return new GetRatingSummaryUseCase(reviewRepository);
    }

    @Bean
    public HelpfulVoteUseCase helpfulVoteUseCase(ReviewRepository reviewRepository,
                                                   EventPublisher eventPublisher) {
        return new HelpfulVoteUseCase(reviewRepository, eventPublisher);
    }
}
