package com.dsports.review.interfaces;

import com.dsports.review.application.command.ApproveReviewCommand;
import com.dsports.review.application.command.CreateReviewCommand;
import com.dsports.review.application.command.HelpfulVoteCommand;
import com.dsports.review.application.command.UpdateReviewCommand;
import com.dsports.review.application.query.GetProductReviewsQuery;
import com.dsports.review.application.query.GetReviewQuery;
import com.dsports.review.application.result.RatingSummaryResult;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.usecase.ApproveReviewUseCase;
import com.dsports.review.application.usecase.CreateReviewUseCase;
import com.dsports.review.application.usecase.GetProductReviewsUseCase;
import com.dsports.review.application.usecase.GetRatingSummaryUseCase;
import com.dsports.review.application.usecase.GetReviewUseCase;
import com.dsports.review.application.usecase.HelpfulVoteUseCase;
import com.dsports.review.application.usecase.UpdateReviewUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private CreateReviewUseCase createReviewUseCase;
    private UpdateReviewUseCase updateReviewUseCase;
    private GetReviewUseCase getReviewUseCase;
    private GetProductReviewsUseCase getProductReviewsUseCase;
    private GetRatingSummaryUseCase getRatingSummaryUseCase;
    private ApproveReviewUseCase approveReviewUseCase;
    private HelpfulVoteUseCase helpfulVoteUseCase;
    private ReviewController controller;
    private UUID userId;
    private UUID productId;
    private ReviewResult sampleResult;

    @BeforeEach
    void setUp() {
        createReviewUseCase = mock(CreateReviewUseCase.class);
        updateReviewUseCase = mock(UpdateReviewUseCase.class);
        getReviewUseCase = mock(GetReviewUseCase.class);
        getProductReviewsUseCase = mock(GetProductReviewsUseCase.class);
        getRatingSummaryUseCase = mock(GetRatingSummaryUseCase.class);
        approveReviewUseCase = mock(ApproveReviewUseCase.class);
        helpfulVoteUseCase = mock(HelpfulVoteUseCase.class);
        controller = new ReviewController(createReviewUseCase, updateReviewUseCase,
                getReviewUseCase, getProductReviewsUseCase, getRatingSummaryUseCase,
                approveReviewUseCase, helpfulVoteUseCase,
                mock(com.dsports.review.application.usecase.GetReviewEligibilityUseCase.class));
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        sampleResult = new ReviewResult(
                UUID.randomUUID(), productId, UUID.randomUUID(),
                5, "Great product", "Highly recommended",
                true, 3, "APPROVED",
                Instant.now(), Instant.now(), 0);
    }

    @Test
    void shouldCreateReview() {
        var request = new ReviewController.CreateReviewRequest(productId, UUID.randomUUID(), 5, "Great", "Comment");
        when(createReviewUseCase.execute(any(CreateReviewCommand.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.createReview(auth, request))
                .expectNextMatches(r -> r.getStatusCode().is2xxSuccessful())
                .verifyComplete();
    }

    @Test
    void shouldUpdateReview() {
        var reviewId = sampleResult.id();
        var request = new ReviewController.UpdateReviewRequest(4, "Updated title", "Updated comment");
        when(updateReviewUseCase.execute(any(UpdateReviewCommand.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.updateReview(auth, reviewId, request))
                .expectNext(sampleResult)
                .verifyComplete();
    }

    @Test
    void shouldGetReviewById() {
        var reviewId = sampleResult.id();
        when(getReviewUseCase.execute(any(GetReviewQuery.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.getReview(auth, reviewId))
                .expectNextMatches(r -> r.getStatusCode().is2xxSuccessful())
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundForMissingReview() {
        var reviewId = UUID.randomUUID();
        when(getReviewUseCase.execute(any(GetReviewQuery.class))).thenReturn(Mono.empty());
        var auth = userAuth(userId);

        StepVerifier.create(controller.getReview(auth, reviewId))
                .expectNextMatches(r -> r.getStatusCode().is4xxClientError())
                .verifyComplete();
    }

    @Test
    void shouldGetProductReviews() {
        when(getProductReviewsUseCase.execute(any(GetProductReviewsQuery.class))).thenReturn(Flux.just(sampleResult));

        StepVerifier.create(controller.getProductReviews(productId, 0, 20))
                .expectNext(sampleResult)
                .verifyComplete();
    }

    @Test
    void shouldGetRatingSummary() {
        var summary = new RatingSummaryResult(productId, 4.5, 10,
                Map.of(1, 0, 2, 0, 3, 1, 4, 3, 5, 6),
                0, 0, 1, 3, 6);
        when(getRatingSummaryUseCase.execute(productId)).thenReturn(Mono.just(summary));

        StepVerifier.create(controller.getRatingSummary(productId))
                .expectNext(summary)
                .verifyComplete();
    }

    @Test
    void shouldVoteHelpful() {
        var reviewId = sampleResult.id();
        when(helpfulVoteUseCase.execute(any(HelpfulVoteCommand.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.voteHelpful(auth, reviewId))
                .expectNext(sampleResult)
                .verifyComplete();
    }

    @Test
    void shouldModerateReviewAsAdmin() {
        var reviewId = sampleResult.id();
        var request = new ReviewController.ModerateReviewRequest(true);
        when(approveReviewUseCase.execute(any(ApproveReviewCommand.class))).thenReturn(Mono.just(sampleResult));
        var auth = adminAuth(userId);

        StepVerifier.create(controller.moderateReview(auth, reviewId, request))
                .expectNext(sampleResult)
                .verifyComplete();
    }

    @Test
    void shouldReturnForbiddenForNonAdminModeration() {
        var reviewId = UUID.randomUUID();
        var request = new ReviewController.ModerateReviewRequest(true);
        var auth = userAuth(userId);

        StepVerifier.create(controller.moderateReview(auth, reviewId, request))
                .expectError()
                .verify();
    }

    private Authentication userAuth(UUID uid) {
        return new Authentication() {
            @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
            @Override public Object getCredentials() { return null; }
            @Override public Object getDetails() { return null; }
            @Override public Object getPrincipal() { return uid.toString(); }
            @Override public boolean isAuthenticated() { return true; }
            @Override public void setAuthenticated(boolean isAuthenticated) {}
            @Override public String getName() { return uid.toString(); }
        };
    }

    private Authentication adminAuth(UUID uid) {
        return new Authentication() {
            @Override public Collection<? extends GrantedAuthority> getAuthorities() {
                return List.of(() -> "ROLE_ADMIN");
            }
            @Override public Object getCredentials() { return null; }
            @Override public Object getDetails() { return null; }
            @Override public Object getPrincipal() { return uid.toString(); }
            @Override public boolean isAuthenticated() { return true; }
            @Override public void setAuthenticated(boolean isAuthenticated) {}
            @Override public String getName() { return uid.toString(); }
        };
    }
}
