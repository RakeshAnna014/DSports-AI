package com.dsports.exception;

import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
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

    private static final Map<InventoryErrorCode, HttpStatus> INVENTORY_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(InventoryErrorCode.INVENTORY_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(InventoryErrorCode.DUPLICATE_INVENTORY, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.INVALID_QUANTITY, HttpStatus.BAD_REQUEST),
        Map.entry(InventoryErrorCode.INVALID_RESERVED_QUANTITY, HttpStatus.BAD_REQUEST),
        Map.entry(InventoryErrorCode.INVALID_REORDER_LEVEL, HttpStatus.BAD_REQUEST),
        Map.entry(InventoryErrorCode.INSUFFICIENT_STOCK, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.RESERVATION_EXCEEDS_AVAILABLE, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.STOCK_OUT_EXCEEDS_AVAILABLE, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.CANNOT_RESERVE_UNAVAILABLE_STOCK, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.CANNOT_STOCK_OUT_RESERVED_QUANTITY, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(InventoryErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(InventoryErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    private static final Map<PricingErrorCode, HttpStatus> PRICING_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(PricingErrorCode.PRICE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(PricingErrorCode.DUPLICATE_ACTIVE_PRICE, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST),
        Map.entry(PricingErrorCode.INVALID_CURRENCY, HttpStatus.BAD_REQUEST),
        Map.entry(PricingErrorCode.INVALID_EFFECTIVE_DATE, HttpStatus.BAD_REQUEST),
        Map.entry(PricingErrorCode.CANNOT_MODIFY_ARCHIVED, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.CANNOT_SCHEDULE_NON_DRAFT, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.CANNOT_ACTIVATE_ARCHIVED, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.OVERLAPPING_ACTIVE_PRICE, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(PricingErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(PricingErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(PricingErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    private static final Map<CatalogErrorCode, HttpStatus> CATALOG_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(CatalogErrorCode.INVALID_SPORT_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_CATEGORY_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_BRAND_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_SLUG, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_SKU, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_PRODUCT_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.INVALID_IMAGE_URL, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.SPORT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CatalogErrorCode.CATEGORY_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CatalogErrorCode.BRAND_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CatalogErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CatalogErrorCode.DUPLICATE_SKU, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.DUPLICATE_SPORT_NAME, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.DUPLICATE_CATEGORY_NAME, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.DUPLICATE_BRAND_NAME, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.DUPLICATE_SLUG, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.ARCHIVED_ENTITY, HttpStatus.CONFLICT),
        Map.entry(CatalogErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(CatalogErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(CatalogErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    private static final Map<CartErrorCode, HttpStatus> CART_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(CartErrorCode.CART_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CartErrorCode.CART_NOT_ACTIVE, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.DUPLICATE_ACTIVE_CART, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.ITEM_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CartErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CartErrorCode.PRODUCT_NOT_ACTIVE, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.INSUFFICIENT_STOCK, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.PRICE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CartErrorCode.INVALID_QUANTITY, HttpStatus.BAD_REQUEST),
        Map.entry(CartErrorCode.MAX_QUANTITY_EXCEEDED, HttpStatus.BAD_REQUEST),
        Map.entry(CartErrorCode.MAX_ITEMS_EXCEEDED, HttpStatus.BAD_REQUEST),
        Map.entry(CartErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST),
        Map.entry(CartErrorCode.INVALID_USER, HttpStatus.UNAUTHORIZED),
        Map.entry(CartErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(CartErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(CartErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(CartErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(IdentityDomainException.class)
    public Mono<ApiError> handleIdentityDomainException(IdentityDomainException ex, ServerWebExchange exchange) {
        var status = ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    @ExceptionHandler(InventoryDomainException.class)
    public Mono<ApiError> handleInventoryDomainException(InventoryDomainException ex, ServerWebExchange exchange) {
        var status = INVENTORY_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    @ExceptionHandler(PricingDomainException.class)
    public Mono<ApiError> handlePricingDomainException(PricingDomainException ex, ServerWebExchange exchange) {
        var status = PRICING_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    @ExceptionHandler(CatalogDomainException.class)
    public Mono<ApiError> handleCatalogDomainException(CatalogDomainException ex, ServerWebExchange exchange) {
        var status = CATALOG_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    private static final Map<CheckoutErrorCode, HttpStatus> CHECKOUT_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(CheckoutErrorCode.CHECKOUT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CheckoutErrorCode.CART_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CheckoutErrorCode.CART_EMPTY, HttpStatus.BAD_REQUEST),
        Map.entry(CheckoutErrorCode.CHECKOUT_EMPTY, HttpStatus.BAD_REQUEST),
        Map.entry(CheckoutErrorCode.CHECKOUT_EXPIRED, HttpStatus.GONE),
        Map.entry(CheckoutErrorCode.CHECKOUT_ALREADY_TERMINAL, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER, HttpStatus.FORBIDDEN),
        Map.entry(CheckoutErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.ITEM_OUT_OF_STOCK, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CheckoutErrorCode.PRICE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CheckoutErrorCode.MISSING_SHIPPING_ADDRESS, HttpStatus.BAD_REQUEST),
        Map.entry(CheckoutErrorCode.MISSING_DELIVERY_METHOD, HttpStatus.BAD_REQUEST)
    );

    @ExceptionHandler(CartDomainException.class)
    public Mono<ApiError> handleCartDomainException(CartDomainException ex, ServerWebExchange exchange) {
        var status = CART_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    @ExceptionHandler(CheckoutDomainException.class)
    public Mono<ApiError> handleCheckoutDomainException(CheckoutDomainException ex, ServerWebExchange exchange) {
        var status = CHECKOUT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    private static final Map<OrderErrorCode, HttpStatus> ORDER_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(OrderErrorCode.ORDER_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(OrderErrorCode.ORDER_EMPTY, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.ORDER_ALREADY_CANCELLED, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.ORDER_ALREADY_DELIVERED, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.CHECKOUT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(OrderErrorCode.CHECKOUT_NOT_VALIDATED, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.CART_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(OrderErrorCode.CART_EMPTY, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.INSUFFICIENT_STOCK, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.DUPLICATE_ORDER, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(OrderErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(OrderDomainException.class)
    public Mono<ApiError> handleOrderDomainException(OrderDomainException ex, ServerWebExchange exchange) {
        var status = ORDER_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        var apiError = buildApiError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
        logStatus(status, ex.getMessage());
        return Mono.just(apiError);
    }

    private static final Map<PaymentErrorCode, HttpStatus> PAYMENT_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(PaymentErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(PaymentErrorCode.DUPLICATE_PAYMENT, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.PAYMENT_ALREADY_SUCCESSFUL, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.PAYMENT_ALREADY_CANCELLED, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.PAYMENT_ALREADY_REFUNDED, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.PAYMENT_ALREADY_FAILED, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.AMOUNT_MISMATCH, HttpStatus.BAD_REQUEST),
        Map.entry(PaymentErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(PaymentErrorCode.ORDER_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(PaymentErrorCode.ORDER_ALREADY_PAID, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.CANNOT_REFUND_NON_SUCCESS_PAYMENT, HttpStatus.BAD_REQUEST),
        Map.entry(PaymentErrorCode.CANNOT_CANCEL_TERMINAL_PAYMENT, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.GATEWAY_ERROR, HttpStatus.BAD_GATEWAY),
        Map.entry(PaymentErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(PaymentErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(PaymentErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(PaymentDomainException.class)
    public Mono<ApiError> handlePaymentDomainException(PaymentDomainException ex, ServerWebExchange exchange) {
        var status = PAYMENT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
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
