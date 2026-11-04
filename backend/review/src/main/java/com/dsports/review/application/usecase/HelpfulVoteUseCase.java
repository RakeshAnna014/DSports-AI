package com.dsports.review.application.usecase;

import com.dsports.review.application.command.HelpfulVoteCommand;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.HelpfulVote;
import com.dsports.review.domain.model.ReviewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class HelpfulVoteUseCase {
    private static final Logger log = LoggerFactory.getLogger(HelpfulVoteUseCase.class);

    private final ReviewRepository repository;
    private final EventPublisher eventPublisher;

    public HelpfulVoteUseCase(ReviewRepository repository,
                               EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ReviewResult> execute(HelpfulVoteCommand command) {
        var reviewId = ReviewId.fromUUID(command.reviewId());

        return repository.findById(reviewId)
            .switchIfEmpty(Mono.error(new ReviewDomainException(
                ReviewErrorCode.REVIEW_NOT_FOUND,
                "Review not found: " + reviewId
            )))
            .flatMap(review -> repository.existsHelpfulVote(reviewId, command.userId())
                .flatMap(alreadyVoted -> {
                    if (alreadyVoted) {
                        log.info("User {} already voted helpful on review {}", command.userId(), reviewId);
                        return Mono.just(ReviewResultMapper.toResult(review));
                    }
                    var vote = new HelpfulVote(UUID.randomUUID(), reviewId, command.userId());
                    return repository.saveHelpfulVote(vote)
                        .then(Mono.defer(() -> {
                            review.markHelpful();
                            return repository.save(review);
                        }))
                        .then(Mono.defer(() -> {
                            review.getDomainEvents().forEach(eventPublisher::publish);
                            review.clearDomainEvents();
                            log.info("User {} marked review {} as helpful", command.userId(), reviewId);
                            return Mono.just(ReviewResultMapper.toResult(review));
                        }));
                }));
    }
}
