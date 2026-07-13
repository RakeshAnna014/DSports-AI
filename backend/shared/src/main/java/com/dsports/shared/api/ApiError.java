package com.dsports.shared.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
    int status,
    String error,
    String code,
    String message,
    String path,
    String correlationId,
    Instant timestamp,
    List<ValidationError> validationErrors
) {
    public static ApiError of(int status, String error, String code, String message, String path, String correlationId) {
        return new ApiError(status, error, code, message, path, correlationId, Instant.now(), null);
    }

    public ApiError withValidationErrors(List<ValidationError> validationErrors) {
        return new ApiError(status, error, code, message, path, correlationId, timestamp, validationErrors);
    }

    public record ValidationError(String field, String message) {}
}
