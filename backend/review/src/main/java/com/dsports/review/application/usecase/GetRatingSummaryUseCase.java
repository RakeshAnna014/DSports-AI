package com.dsports.review.application.usecase;

import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.RatingSummaryResult;
import com.dsports.review.domain.model.Review;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GetRatingSummaryUseCase {
    private static final Logger log = LoggerFactory.getLogger(GetRatingSummaryUseCase.class);

    private final ReviewRepository repository;

    public GetRatingSummaryUseCase(ReviewRepository repository) {
        this.repository = repository;
    }

    public Mono<RatingSummaryResult> execute(UUID productId) {
        return repository.findApprovedByProductId(productId, 0, Integer.MAX_VALUE)
            .filter(review -> review.getStatus() == com.dsports.review.domain.model.ReviewStatus.APPROVED)
            .collectList()
            .map(reviews -> {
                int totalReviews = reviews.size();
                double sum = 0;
                Map<Integer, Integer> distribution = new HashMap<>();
                int r1 = 0, r2 = 0, r3 = 0, r4 = 0, r5 = 0;

                for (Review review : reviews) {
                    int value = review.getRating().value();
                    sum += value;
                    distribution.merge(value, 1, Integer::sum);
                    switch (value) {
                        case 1 -> r1++;
                        case 2 -> r2++;
                        case 3 -> r3++;
                        case 4 -> r4++;
                        case 5 -> r5++;
                    }
                }

                double average = totalReviews > 0 ? sum / totalReviews : 0.0;

                return new RatingSummaryResult(
                    productId,
                    average,
                    totalReviews,
                    distribution,
                    r1, r2, r3, r4, r5
                );
            });
    }
}
