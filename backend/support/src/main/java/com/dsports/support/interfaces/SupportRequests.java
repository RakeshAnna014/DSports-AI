package com.dsports.support.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request bodies of the support API. Customer identity is never part of any of them: it comes from the authentication. */
public final class SupportRequests {

    private SupportRequests() {}

    public record EvidenceDto(@Size(max = 30) String kind, @Size(max = 100) String ref, @NotBlank @Size(max = 400) String summary) {}

    public record CreateCaseRequest(
        @NotBlank @Size(max = 30) String category,
        @Size(max = 60) String subcategory,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 2000) String description,
        UUID orderId,
        UUID productId,
        UUID paymentId,
        UUID returnId,
        @Size(max = 100) String refundReference,
        boolean safetyRelated,
        @Size(max = 4000) String investigationSummary,
        @Valid @Size(max = 20) List<EvidenceDto> evidence,
        @Size(max = 10) List<@Size(max = 200) String> policyCitations,
        @Size(max = 10) List<@Size(max = 200) String> missingInformation,
        @Size(max = 1000) String resolutionRecommendation,
        @Size(max = 500) String escalationReason,
        @Size(max = 20) String source) {}

    public record MessageRequest(@NotBlank @Size(max = 2000) String body, Integer expectedVersion) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record AssignRequest(@Size(max = 40) String queue, boolean takeIt, Integer expectedVersion) {}

    public record PriorityRequest(@NotNull String priority, @NotBlank @Size(max = 500) String reason, Integer expectedVersion) {}

    public record StatusRequest(@NotNull String status, @Size(max = 500) String note, Integer expectedVersion) {}

    public record ResolveRequest(@NotNull String outcome, @NotBlank @Size(max = 1000) String note, @Size(max = 10) List<@Size(max = 100) String> evidenceRefs,
                                 Integer expectedVersion) {}
}
