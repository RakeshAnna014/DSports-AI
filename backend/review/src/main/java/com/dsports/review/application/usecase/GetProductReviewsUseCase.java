package com.dsports.review.application.usecase;

import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.query.GetProductReviewsQuery;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class GetProductReviewsUseCase {
    private static final Logger log = LoggerFactory.getLogger(GetProductReviewsUseCase.class);

    private final ReviewRepository repository;

    public GetProductReviewsUseCase(ReviewRepository repository) {
        this.repository = repository;
    }

    public Flux<ReviewResult> execute(GetProductReviewsQuery query) {
        return repository.findApprovedByProductId(query.productId(), query.page(), query.size())
            .map(ReviewResultMapper::toResult);
    }
}
