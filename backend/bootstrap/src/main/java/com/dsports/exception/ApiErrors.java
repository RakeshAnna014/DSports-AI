package com.dsports.exception;

import com.dsports.config.CorrelationIdFilter;
import com.dsports.shared.api.ApiError;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

/**
 * Builds the single {@link ApiError} contract used by the exception handler and the security layer,
 * so every error response carries the same shape, the real HTTP status and the request's correlation ID.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiError of(HttpStatusCode status, String code, String message, ServerWebExchange exchange) {
        var resolved = HttpStatus.resolve(status.value());
        var reason = resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
        return ApiError.of(
            status.value(),
            reason,
            code,
            message,
            exchange.getRequest().getPath().value(),
            correlationId(exchange)
        );
    }

    static String correlationId(ServerWebExchange exchange) {
        var fromRequest = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (fromRequest != null && !fromRequest.isBlank()) {
            return fromRequest;
        }
        return exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
    }
}
