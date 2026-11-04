package com.dsports.exception;

import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Verifies the HTTP status line, not just the body, for every error category of the API error contract.
 */
class GlobalExceptionHandlerHttpTest {

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new ErrorScenarioController())
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void validationFailureReturns400WithFieldErrors() {
        client.post().uri("/scenarios/body").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"\"}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.validationErrors[0].field").isEqualTo("name");
    }

    @Test
    void malformedJsonReturns400() {
        client.post().uri("/scenarios/body").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{bad")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void invalidUuidPathVariableReturns400() {
        client.get().uri("/scenarios/items/not-a-uuid")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_PARAMETER")
            .jsonPath("$.message").isEqualTo("Invalid value for parameter 'id'");
    }

    @Test
    void missingRequiredParameterReturns400() {
        client.get().uri("/scenarios/param")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void domainValidationErrorReturns400() {
        client.get().uri("/scenarios/catalog-validation")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void controllerUnauthenticatedReturns401() {
        client.get().uri("/scenarios/unauthenticated")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void accessDeniedReturns403() {
        client.get().uri("/scenarios/forbidden")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void domainNotFoundReturns404() {
        client.get().uri("/scenarios/not-found")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void unknownRouteReturns404() {
        client.get().uri("/no/such/route")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void wrongMethodReturns405() {
        client.delete().uri("/scenarios/not-found")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectBody()
            .jsonPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void businessConflictReturns409() {
        client.get().uri("/scenarios/conflict")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void optimisticLockingReturns409() {
        client.get().uri("/scenarios/optimistic-lock")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("OPTIMISTIC_LOCKING_CONFLICT");
    }

    @Test
    void dataIntegrityViolationReturns409WithoutSqlDetails() {
        client.get().uri("/scenarios/data-integrity")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("DATA_CONFLICT")
            .jsonPath("$.message").isEqualTo("The request conflicts with existing data");
    }

    @Test
    void unexpectedFailureReturns500WithoutInternalDetails() {
        client.get().uri("/scenarios/unexpected")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.message").isEqualTo("An unexpected error occurred");
    }

    @Test
    void domainInternalErrorDoesNotLeakMessage() {
        client.get().uri("/scenarios/domain-internal")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.message").isEqualTo("An unexpected error occurred");
    }

    @Test
    void correlationIdIsEchoedInErrorBody() {
        client.get().uri("/scenarios/not-found")
            .header("X-Correlation-Id", "corr-42")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.correlationId").isEqualTo("corr-42");
    }

    record ScenarioRequest(@NotBlank String name) {}

    @RestController
    @RequestMapping("/scenarios")
    static class ErrorScenarioController {

        @PostMapping("/body")
        Mono<Void> body(@Valid @RequestBody ScenarioRequest request) {
            return Mono.empty();
        }

        @GetMapping("/items/{id}")
        Mono<String> item(@PathVariable UUID id) {
            return Mono.just(id.toString());
        }

        @GetMapping("/param")
        Mono<String> param(@RequestParam String required) {
            return Mono.just(required);
        }

        @GetMapping("/catalog-validation")
        Mono<Void> catalogValidation() {
            return Mono.error(new CatalogDomainException(CatalogErrorCode.VALIDATION_ERROR, "Invalid sort column"));
        }

        @GetMapping("/unauthenticated")
        Mono<Void> unauthenticated() {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }

        @GetMapping("/forbidden")
        Mono<Void> forbidden() {
            return Mono.error(new AccessDeniedException("Access Denied"));
        }

        @GetMapping("/not-found")
        Mono<Void> notFound() {
            return Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND, "Product not found"));
        }

        @GetMapping("/conflict")
        Mono<Void> conflict() {
            return Mono.error(new CartDomainException(CartErrorCode.INSUFFICIENT_STOCK, "Only 1 left"));
        }

        @GetMapping("/optimistic-lock")
        Mono<Void> optimisticLock() {
            return Mono.error(new OptimisticLockingFailureException("version mismatch"));
        }

        @GetMapping("/data-integrity")
        Mono<Void> dataIntegrity() {
            return Mono.error(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_x\""));
        }

        @GetMapping("/unexpected")
        Mono<Void> unexpected() {
            return Mono.error(new IllegalStateException("NullPointer deep inside"));
        }

        @GetMapping("/domain-internal")
        Mono<Void> domainInternal() {
            return Mono.error(new CartDomainException(CartErrorCode.GENERIC, "Database constraint violation: secret SQL"));
        }
    }
}
