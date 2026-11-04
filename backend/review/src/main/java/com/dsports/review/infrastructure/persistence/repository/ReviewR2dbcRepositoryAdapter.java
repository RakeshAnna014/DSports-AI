package com.dsports.review.infrastructure.persistence.repository;

import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.HelpfulVote;
import com.dsports.review.domain.model.Review;
import com.dsports.review.domain.model.ReviewId;
import com.dsports.review.infrastructure.persistence.mapper.ReviewEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ReviewR2dbcRepositoryAdapter implements ReviewRepository {
    private final SpringR2dbcReviewRepository reviewRepository;
    private final SpringR2dbcHelpfulVoteRepository helpfulVoteRepository;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator transactionalOperator;

    public ReviewR2dbcRepositoryAdapter(SpringR2dbcReviewRepository reviewRepository,
                                         SpringR2dbcHelpfulVoteRepository helpfulVoteRepository,
                                         EventPublisher eventPublisher,
                                         ReactiveTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.helpfulVoteRepository = helpfulVoteRepository;
        this.eventPublisher = eventPublisher;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public Mono<Review> findById(ReviewId id) {
        return reviewRepository.findById(id.value())
                .map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Mono<Review> findByProductIdAndUserId(UUID productId, UUID userId) {
        return reviewRepository.findByProductIdAndUserId(productId, userId).map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Flux<Review> findApprovedByProductId(UUID productId, int page, int size) {
        var offset = page * size;
        return reviewRepository.findApprovedByProductIdPaged(productId, offset, size)
                .map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Mono<Long> countByProductId(UUID productId) {
        return reviewRepository.countByProductId(productId);
    }

    @Override
    public Flux<Review> findByUserId(UUID userId) {
        return reviewRepository.findByUserId(userId)
                .map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Flux<Review> findAllPending(int page, int size) {
        var offset = page * size;
        return reviewRepository.findByStatusPaged("PENDING", offset, size)
                .map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Mono<Long> countPending() {
        return reviewRepository.countByStatus("PENDING");
    }

    @Override
    public Flux<Review> findAll(int page, int size) {
        var offset = page * size;
        return reviewRepository.findAllPaged(offset, size)
                .map(ReviewEntityMapper::toDomain);
    }

    @Override
    public Mono<Long> countAll() {
        return reviewRepository.count();
    }

    @Override
    public Mono<Boolean> existsByProductIdAndUserId(UUID productId, UUID userId) {
        return reviewRepository.existsByProductIdAndUserId(productId, userId);
    }

    @Override
    public Mono<Void> save(Review review) {
        var entity = ReviewEntityMapper.toEntity(review);
        var id = review.getId().value();

        return transactionalOperator.execute(tx ->
            reviewRepository.save(entity)
                    .onErrorMap(OptimisticLockingFailureException.class, e ->
                            new ReviewDomainException(ReviewErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                    "Concurrent modification detected for review " + id))
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                            new ReviewDomainException(ReviewErrorCode.DUPLICATE_REVIEW,
                                    "You have already reviewed product " + review.getProductId()))
                    .map(savedEntity -> {
                        review.setVersion(savedEntity.getVersion());
                        return savedEntity;
                    })
                    .then()
        ).then(Mono.fromRunnable(() -> publishEvents(review)));
    }

    @Override
    public Mono<Void> saveHelpfulVote(HelpfulVote vote) {
        var entity = ReviewEntityMapper.toHelpfulVoteEntity(vote);
        return transactionalOperator.execute(tx ->
            helpfulVoteRepository.save(entity).then()
        ).then();
    }

    @Override
    public Mono<Boolean> existsHelpfulVote(ReviewId reviewId, UUID userId) {
        return helpfulVoteRepository.existsByReviewIdAndUserId(reviewId.value(), userId);
    }

    private void publishEvents(Review review) {
        if (!review.getDomainEvents().isEmpty()) {
            review.getDomainEvents().forEach(eventPublisher::publish);
            review.clearDomainEvents();
        }
    }
}
