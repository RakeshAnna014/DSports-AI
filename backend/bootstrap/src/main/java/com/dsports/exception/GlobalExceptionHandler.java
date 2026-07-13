package com.dsports.exception;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.api.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<ErrorCode, HttpStatus> ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(ErrorCode.DUPLICATE_EMAIL, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.INVALID_EMAIL, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_PHONE_NUMBER, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_CUSTOMER_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.MISSING_ROLE, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.DUPLICATE_ROLE, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.OAUTH_PROVIDER_ALREADY_LINKED, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.MAX_AUTH_PROVIDERS_EXCEEDED, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ErrorCode.INVALID_PASSWORD, HttpStatus.UNAUTHORIZED),
        Map.entry(ErrorCode.ACCOUNT_LOCKED, HttpStatus.LOCKED),
        Map.entry(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(IdentityDomainException.class)
    public Mono<ApiError> handleIdentityDomainException(IdentityDomainException ex, ServerWebExchange exchange) {
        var status = ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ApiError> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        var validationErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError.ValidationError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        var apiError = buildApiError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Validation failed", exchange)
            .withValidationErrors(validationErrors);
        log.warn("Validation failed for {}: {} validation errors", exchange.getRequest().getPath(), validationErrors.size());
        return Mono.just(apiError);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Mono<ApiError> handleConstraintViolation(ConstraintViolationException ex, ServerWebExchange exchange) {
        var validationErrors = ex.getConstraintViolations().stream()
            .map(cv -> new ApiError.ValidationError(cv.getPropertyPath().toString(), cv.getMessage()))
            .toList();
        var apiError = buildApiError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Validation failed", exchange)
            .withValidationErrors(validationErrors);
        log.warn("Constraint violation for {}: {} errors", exchange.getRequest().getPath(), validationErrors.size());
        return Mono.just(apiError);
    }

    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public Mono<ApiError> handleUnsupportedMediaType(UnsupportedMediaTypeStatusException ex, ServerWebExchange exchange) {
        var apiError = buildApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", ex.getReason(), exchange);
        log.warn("Unsupported media type for {}: {}", exchange.getRequest().getPath(), ex.getContentType());
        return Mono.just(apiError);
    }

    @ExceptionHandler(Exception.class)
    public Mono<ApiError> handleUnknown(Exception ex, ServerWebExchange exchange) {
        var apiError = buildApiError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(), "An unexpected error occurred", exchange);
        log.error("Unexpected error processing {}: {}", exchange.getRequest().getPath(), ex.getMessage(), ex);
        return Mono.just(apiError);
    }

    private static ApiError buildApiError(HttpStatus status, String code, String message, ServerWebExchange exchange) {
        var correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        return ApiError.of(
            status.value(),
            status.getReasonPhrase(),
            code,
            message,
            exchange.getRequest().getPath().value(),
            correlationId
        );
    }

    private static void logStatus(HttpStatus status, String message) {
        if (status.is5xxServerError()) {
            log.error("{}: {}", status.value(), message);
        } else {
            log.warn("{}: {}", status.value(), message);
        }
    }
}
