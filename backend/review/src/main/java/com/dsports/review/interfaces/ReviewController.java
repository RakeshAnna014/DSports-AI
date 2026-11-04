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
import com.dsports.review.application.usecase.GetReviewEligibilityUseCase;
import com.dsports.review.application.usecase.GetReviewUseCase;
import com.dsports.review.application.usecase.HelpfulVoteUseCase;
import com.dsports.review.application.usecase.UpdateReviewUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/reviews", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reviews")
public class ReviewController {

    private final CreateReviewUseCase createReviewUseCase;
    private final UpdateReviewUseCase updateReviewUseCase;
    private final GetReviewUseCase getReviewUseCase;
    private final GetProductReviewsUseCase getProductReviewsUseCase;
    private final GetRatingSummaryUseCase getRatingSummaryUseCase;
    private final ApproveReviewUseCase approveReviewUseCase;
    private final HelpfulVoteUseCase helpfulVoteUseCase;
    private final GetReviewEligibilityUseCase getReviewEligibilityUseCase;

    public ReviewController(CreateReviewUseCase createReviewUseCase,
                             UpdateReviewUseCase updateReviewUseCase,
                             GetReviewUseCase getReviewUseCase,
                             GetProductReviewsUseCase getProductReviewsUseCase,
                             GetRatingSummaryUseCase getRatingSummaryUseCase,
                             ApproveReviewUseCase approveReviewUseCase,
                             HelpfulVoteUseCase helpfulVoteUseCase,
                             GetReviewEligibilityUseCase getReviewEligibilityUseCase) {
        this.createReviewUseCase = createReviewUseCase;
        this.updateReviewUseCase = updateReviewUseCase;
        this.getReviewUseCase = getReviewUseCase;
        this.getProductReviewsUseCase = getProductReviewsUseCase;
        this.getRatingSummaryUseCase = getRatingSummaryUseCase;
        this.approveReviewUseCase = approveReviewUseCase;
        this.helpfulVoteUseCase = helpfulVoteUseCase;
        this.getReviewEligibilityUseCase = getReviewEligibilityUseCase;
    }

    @GetMapping("/eligibility")
    @Operation(summary = "Can the current customer review this product?",
               description = "Returns the delivered order to attach the review to, or why a review is not possible.")
    public Mono<GetReviewEligibilityUseCase.Eligibility> getEligibility(
            Authentication authentication,
            @Parameter(description = "Product ID") @RequestParam UUID productId) {
        return getReviewEligibilityUseCase.execute(productId, extractUserId(authentication));
    }

    @PostMapping
    @Operation(summary = "Create a review",
               description = "Creates a product review after a verified purchase. Requires authentication.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Review created",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error or product not purchased"),
        @ApiResponse(responseCode = "409", description = "Duplicate review"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<ReviewResult>> createReview(
            Authentication authentication,
            @Valid @RequestBody CreateReviewRequest request) {
        var userId = extractUserId(authentication);
        var command = new CreateReviewCommand(
                request.productId(), request.orderId(), userId,
                request.rating(), request.title(), request.comment());
        return createReviewUseCase.execute(command)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a review",
               description = "Updates an existing review. Only the review owner can update.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Review updated",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "404", description = "Review not found"),
        @ApiResponse(responseCode = "403", description = "Forbidden — not the review owner"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ReviewResult> updateReview(
            Authentication authentication,
            @Parameter(description = "Review ID") @PathVariable UUID id,
            @Valid @RequestBody UpdateReviewRequest request) {
        var userId = extractUserId(authentication);
        var command = new UpdateReviewCommand(
                id, userId, request.rating(), request.title(), request.comment());
        return updateReviewUseCase.execute(command);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get review by ID",
               description = "Returns a specific review. Users can only access their own reviews unless admin.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Review found",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "404", description = "Review not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<ReviewResult>> getReview(
            Authentication authentication,
            @Parameter(description = "Review ID") @PathVariable UUID id) {
        var userId = extractUserId(authentication);
        var isAdmin = isAdmin(authentication);
        var query = new GetReviewQuery(id, userId, isAdmin);
        return getReviewUseCase.execute(query)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/product/{productId}")
    @Operation(summary = "Get product reviews",
               description = "Returns paginated reviews for a product. Public endpoint.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Reviews retrieved",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Flux<ReviewResult> getProductReviews(
            @Parameter(description = "Product ID") @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-based)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size") int size) {
        var query = new GetProductReviewsQuery(productId, page, size);
        return getProductReviewsUseCase.execute(query);
    }

    @GetMapping("/product/{productId}/summary")
    @Operation(summary = "Get rating summary",
               description = "Returns aggregate rating summary for a product. Public endpoint.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Rating summary retrieved",
                     content = @Content(schema = @Schema(implementation = RatingSummaryResult.class)))
    })
    public Mono<RatingSummaryResult> getRatingSummary(
            @Parameter(description = "Product ID") @PathVariable UUID productId) {
        return getRatingSummaryUseCase.execute(productId);
    }

    @PostMapping("/admin/{id}/moderate")
    @Operation(summary = "Moderate a review",
               description = "Approves or rejects a pending review. Admin only.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Review moderated",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires admin role"),
        @ApiResponse(responseCode = "404", description = "Review not found")
    })
    public Mono<ReviewResult> moderateReview(
            Authentication authentication,
            @Parameter(description = "Review ID") @PathVariable UUID id,
            @Valid @RequestBody ModerateReviewRequest request) {
        if (!isAdmin(authentication)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"));
        }
        var command = new ApproveReviewCommand(id, request.approve());
        return approveReviewUseCase.execute(command);
    }

    @PostMapping("/{id}/helpful")
    @Operation(summary = "Mark review as helpful",
               description = "Votes a review as helpful. Users can vote once per review.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Vote recorded",
                     content = @Content(schema = @Schema(implementation = ReviewResult.class))),
        @ApiResponse(responseCode = "409", description = "Already voted"),
        @ApiResponse(responseCode = "404", description = "Review not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ReviewResult> voteHelpful(
            Authentication authentication,
            @Parameter(description = "Review ID") @PathVariable UUID id) {
        var userId = extractUserId(authentication);
        var command = new HelpfulVoteCommand(id, userId);
        return helpfulVoteUseCase.execute(command);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public record CreateReviewRequest(
            @NotNull UUID productId,
            @NotNull UUID orderId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String comment) {}

    public record UpdateReviewRequest(
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String comment) {}

    public record ModerateReviewRequest(
            @NotNull Boolean approve) {}
}
