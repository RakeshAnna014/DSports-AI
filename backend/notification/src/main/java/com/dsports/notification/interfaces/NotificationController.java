package com.dsports.notification.interfaces;

import com.dsports.notification.application.command.RetryNotificationCommand;
import com.dsports.notification.application.query.GetNotificationHistoryQuery;
import com.dsports.notification.application.query.GetNotificationQuery;
import com.dsports.notification.application.result.NotificationHistoryResult;
import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.application.usecase.GetNotificationUseCase;
import com.dsports.notification.application.usecase.NotificationHistoryUseCase;
import com.dsports.notification.application.usecase.RetryNotificationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Notifications")
public class NotificationController {

    private final GetNotificationUseCase getNotificationUseCase;
    private final NotificationHistoryUseCase notificationHistoryUseCase;
    private final RetryNotificationUseCase retryNotificationUseCase;

    public NotificationController(GetNotificationUseCase getNotificationUseCase,
                                   NotificationHistoryUseCase notificationHistoryUseCase,
                                   RetryNotificationUseCase retryNotificationUseCase) {
        this.getNotificationUseCase = getNotificationUseCase;
        this.notificationHistoryUseCase = notificationHistoryUseCase;
        this.retryNotificationUseCase = retryNotificationUseCase;
    }

    @GetMapping
    @Operation(summary = "Get notification history",
               description = "Returns paginated notification history for the authenticated user. Admins can view all.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Notification history retrieved",
                     content = @Content(schema = @Schema(implementation = NotificationHistoryResult.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<NotificationHistoryResult> getHistory(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-based)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size") int size) {
        var userId = extractUserId(authentication);
        var isAdmin = isAdmin(authentication);
        var query = new GetNotificationHistoryQuery(userId, isAdmin, page, size);
        return notificationHistoryUseCase.execute(query);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get notification by ID",
               description = "Returns a specific notification. Users can only access their own notifications.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Notification found",
                     content = @Content(schema = @Schema(implementation = NotificationResult.class))),
        @ApiResponse(responseCode = "404", description = "Notification not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<NotificationResult>> getById(
            Authentication authentication,
            @Parameter(description = "Notification ID") @PathVariable UUID id) {
        var userId = extractUserId(authentication);
        var isAdmin = isAdmin(authentication);
        var query = new GetNotificationQuery(id, userId, isAdmin);
        return getNotificationUseCase.execute(query)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed notification",
               description = "Retries sending a failed notification. Admin only.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Notification retry initiated",
                     content = @Content(schema = @Schema(implementation = NotificationResult.class))),
        @ApiResponse(responseCode = "404", description = "Notification not found"),
        @ApiResponse(responseCode = "409", description = "Conflict — notification cannot be retried"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires admin role")
    })
    public Mono<NotificationResult> retry(
            Authentication authentication,
            @Parameter(description = "Notification ID") @PathVariable UUID id) {
        if (!isAdmin(authentication)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"));
        }
        var command = new RetryNotificationCommand(id);
        return retryNotificationUseCase.execute(command);
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
}
