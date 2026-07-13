package com.dsports.exception;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.api.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServerWebExchange createExchange(String path, String correlationId) {
        var request = MockServerHttpRequest.get(path)
            .header("X-Correlation-Id", correlationId)
            .build();
        return MockServerWebExchange.from(request);
    }

    private static ServerWebExchange createExchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    @Test
    void shouldMapDuplicateEmailTo409() {
        var ex = new IdentityDomainException(ErrorCode.DUPLICATE_EMAIL, "Email already registered");
        var exchange = createExchange("/test/errors/duplicate-email", "corr-123");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("DUPLICATE_EMAIL");
        assertThat(result.error()).isEqualTo("Conflict");
        assertThat(result.message()).isEqualTo("Email already registered");
        assertThat(result.path()).isEqualTo("/test/errors/duplicate-email");
        assertThat(result.correlationId()).isEqualTo("corr-123");
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    void shouldMapInvalidEmailTo400() {
        var ex = new IdentityDomainException(ErrorCode.INVALID_EMAIL, "Invalid email format");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.code()).isEqualTo("INVALID_EMAIL");
    }

    @Test
    void shouldMapUserNotFoundTo404() {
        var ex = new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.code()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void shouldMapInvalidStatusTransitionTo409() {
        var ex = new IdentityDomainException(ErrorCode.INVALID_STATUS_TRANSITION, "Cannot transition");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void shouldMapInvalidPasswordTo401() {
        var ex = new IdentityDomainException(ErrorCode.INVALID_PASSWORD, "Invalid password");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.code()).isEqualTo("INVALID_PASSWORD");
    }

    @Test
    void shouldMapAccountLockedTo423() {
        var ex = new IdentityDomainException(ErrorCode.ACCOUNT_LOCKED, "Account locked");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(423);
        assertThat(result.code()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void shouldMapGenericTo500() {
        var ex = new IdentityDomainException(ErrorCode.GENERIC, "Something went wrong");
        var exchange = createExchange("/test");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.code()).isEqualTo("GENERIC");
    }

    @Test
    void shouldMapUnexpectedExceptionTo500() {
        var ex = new RuntimeException("Unexpected error");
        var exchange = createExchange("/test");

        var result = handler.handleUnknown(ex, exchange).block();

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void shouldReturnCorrelationIdFromRequest() {
        var ex = new IdentityDomainException(ErrorCode.DUPLICATE_EMAIL, "Email already registered");
        var exchange = createExchange("/test", "my-corr-id");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.correlationId()).isEqualTo("my-corr-id");
    }

    @Test
    void shouldReturnPath() {
        var ex = new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "Not found");
        var exchange = createExchange("/api/users/123");

        var result = handler.handleIdentityDomainException(ex, exchange).block();

        assertThat(result.path()).isEqualTo("/api/users/123");
    }

    @Test
    void shouldNotExposeInternalMessageForUnknownException() {
        var ex = new RuntimeException("Internal database connection pool exhausted - credentials=admin:pass123");
        var exchange = createExchange("/test");

        var result = handler.handleUnknown(ex, exchange).block();

        assertThat(result.message()).isEqualTo("An unexpected error occurred");
        assertThat(result.message()).doesNotContain("credentials");
        assertThat(result.message()).doesNotContain("pass123");
    }
}
