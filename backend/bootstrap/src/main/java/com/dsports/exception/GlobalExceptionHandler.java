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
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.shared.api.ApiError;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    private static final String ACCESS_DENIED = "ACCESS_DENIED";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String UNEXPECTED_ERROR_MESSAGE = "An unexpected error occurred";

    private final MeterRegistry meterRegistry;

    @Autowired
    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    GlobalExceptionHandler() {
        this(new SimpleMeterRegistry());
    }

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
        Map.entry(ErrorCode.PROFILE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ErrorCode.MAX_FAMILY_PROFILES_EXCEEDED, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.INVALID_DATE_OF_BIRTH, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_ADDRESS, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_POSTAL_CODE, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_COUNTRY, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_STATE, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.INVALID_PROFILE_IMAGE_URL, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorCode.ADDRESS_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ErrorCode.MAX_ADDRESSES_EXCEEDED, HttpStatus.CONFLICT),
        Map.entry(ErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
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
        Map.entry(InventoryErrorCode.RESERVATION_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(InventoryErrorCode.RESERVATION_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(InventoryErrorCode.INVALID_RESERVATION_TRANSITION, HttpStatus.CONFLICT),
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
    public Mono<ResponseEntity<ApiError>> handleIdentityDomainException(IdentityDomainException ex, ServerWebExchange exchange) {
        var status = ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(InventoryDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleInventoryDomainException(InventoryDomainException ex, ServerWebExchange exchange) {
        var status = INVENTORY_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(PricingDomainException.class)
    public Mono<ResponseEntity<ApiError>> handlePricingDomainException(PricingDomainException ex, ServerWebExchange exchange) {
        var status = PRICING_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(CatalogDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleCatalogDomainException(CatalogDomainException ex, ServerWebExchange exchange) {
        var status = CATALOG_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
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
        Map.entry(CheckoutErrorCode.MISSING_DELIVERY_METHOD, HttpStatus.BAD_REQUEST),
        Map.entry(CheckoutErrorCode.ACTIVE_CHECKOUT_EXISTS, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.PROMOTION_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(CheckoutErrorCode.PROMOTION_ALREADY_APPLIED, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.PROMOTION_NOT_EDITABLE, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.PROMOTION_LIMIT_REACHED, HttpStatus.CONFLICT),
        Map.entry(CheckoutErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT)
    );

    @ExceptionHandler(CartDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleCartDomainException(CartDomainException ex, ServerWebExchange exchange) {
        var status = CART_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(CheckoutDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleCheckoutDomainException(CheckoutDomainException ex, ServerWebExchange exchange) {
        var status = CHECKOUT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    private static final Map<NotificationErrorCode, HttpStatus> NOTIFICATION_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(NotificationErrorCode.NOTIFICATION_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(NotificationErrorCode.NOTIFICATION_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(NotificationErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(NotificationErrorCode.INVALID_NOTIFICATION_TYPE, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.INVALID_CHANNEL, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.INVALID_MESSAGE, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.INVALID_EMAIL, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.INVALID_PHONE, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(NotificationErrorCode.UNSUPPORTED_CHANNEL, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.PROVIDER_ERROR, HttpStatus.BAD_GATEWAY),
        Map.entry(NotificationErrorCode.MAX_RETRIES_EXCEEDED, HttpStatus.CONFLICT),
        Map.entry(NotificationErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(NotificationErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(NotificationErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(NotificationErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(NotificationDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleNotificationDomainException(NotificationDomainException ex, ServerWebExchange exchange) {
        var status = NOTIFICATION_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    private static final Map<ReviewErrorCode, HttpStatus> REVIEW_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(ReviewErrorCode.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ReviewErrorCode.REVIEW_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(ReviewErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(ReviewErrorCode.INVALID_RATING, HttpStatus.BAD_REQUEST),
        Map.entry(ReviewErrorCode.INVALID_COMMENT, HttpStatus.BAD_REQUEST),
        Map.entry(ReviewErrorCode.DUPLICATE_REVIEW, HttpStatus.CONFLICT),
        Map.entry(ReviewErrorCode.PRODUCT_NOT_PURCHASED, HttpStatus.BAD_REQUEST),
        Map.entry(ReviewErrorCode.ORDER_NOT_DELIVERED, HttpStatus.BAD_REQUEST),
        Map.entry(ReviewErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ReviewErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(ReviewErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(ReviewErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(ReviewErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(ReviewDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleReviewDomainException(ReviewDomainException ex, ServerWebExchange exchange) {
        var status = REVIEW_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    private static final Map<ShipmentErrorCode, HttpStatus> SHIPMENT_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(ShipmentErrorCode.SHIPMENT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ShipmentErrorCode.SHIPMENT_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(ShipmentErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(ShipmentErrorCode.INVALID_TRACKING_NUMBER, HttpStatus.BAD_REQUEST),
        Map.entry(ShipmentErrorCode.INVALID_SHIPMENT_NUMBER, HttpStatus.BAD_REQUEST),
        Map.entry(ShipmentErrorCode.INVALID_ADDRESS, HttpStatus.BAD_REQUEST),
        Map.entry(ShipmentErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ShipmentErrorCode.ORDER_NOT_CONFIRMED, HttpStatus.BAD_REQUEST),
        Map.entry(ShipmentErrorCode.ORDER_ALREADY_SHIPPED, HttpStatus.CONFLICT),
        Map.entry(ShipmentErrorCode.COURIER_ASSIGNMENT_FAILED, HttpStatus.BAD_GATEWAY),
        Map.entry(ShipmentErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(ShipmentErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(ShipmentErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(ShipmentErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(ShipmentDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleShipmentDomainException(ShipmentDomainException ex, ServerWebExchange exchange) {
        var status = SHIPMENT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
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
        Map.entry(OrderErrorCode.CHECKOUT_EXPIRED, HttpStatus.GONE),
        Map.entry(OrderErrorCode.PAYMENT_IN_PROGRESS, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.CART_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(OrderErrorCode.CART_EMPTY, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.INSUFFICIENT_STOCK, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.DUPLICATE_ORDER, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.PROMOTION_NO_LONGER_VALID, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(OrderErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(OrderDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleOrderDomainException(OrderDomainException ex, ServerWebExchange exchange) {
        var status = ORDER_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
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
        Map.entry(PaymentErrorCode.GATEWAY_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT),
        Map.entry(PaymentErrorCode.PAYMENT_IN_PROGRESS, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.ORDER_NOT_PAYABLE, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.INVALID_REFUND_AMOUNT, HttpStatus.BAD_REQUEST),
        Map.entry(PaymentErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(PaymentErrorCode.REFUND_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(PaymentErrorCode.INVALID_REFUND_STATUS, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.REFUND_ALREADY_IN_PROGRESS, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.REFUND_NOT_ALLOWED, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(PaymentErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(PaymentErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(PaymentErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    private static final Map<WishlistErrorCode, HttpStatus> WISHLIST_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(WishlistErrorCode.WISHLIST_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(WishlistErrorCode.DUPLICATE_WISHLIST, HttpStatus.CONFLICT),
        Map.entry(WishlistErrorCode.ITEM_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(WishlistErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(WishlistErrorCode.PRODUCT_NOT_ACTIVE, HttpStatus.CONFLICT),
        Map.entry(WishlistErrorCode.DUPLICATE_WISHLIST_ITEM, HttpStatus.CONFLICT),
        Map.entry(WishlistErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST),
        Map.entry(WishlistErrorCode.INVALID_USER, HttpStatus.UNAUTHORIZED),
        Map.entry(WishlistErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(WishlistErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(WishlistErrorCode.GENERIC, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(WishlistErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(WishlistDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleWishlistDomainException(WishlistDomainException ex, ServerWebExchange exchange) {
        var status = WISHLIST_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    private static final Map<ReturnsErrorCode, HttpStatus> RETURNS_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(ReturnsErrorCode.RETURN_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ReturnsErrorCode.RETURN_NOT_OWNED_BY_USER, HttpStatus.FORBIDDEN),
        Map.entry(ReturnsErrorCode.NOT_ELIGIBLE, HttpStatus.CONFLICT),
        Map.entry(ReturnsErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(ReturnsErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT),
        Map.entry(ReturnsErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(ReturnsErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(ReturnsErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(ReturnsDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleReturnsDomainException(ReturnsDomainException ex, ServerWebExchange exchange) {
        var status = RETURNS_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    private static final Map<SupportErrorCode, HttpStatus> SUPPORT_ERROR_STATUS_MAP = Map.ofEntries(
        Map.entry(SupportErrorCode.CASE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(SupportErrorCode.RELATED_RECORD_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(SupportErrorCode.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT),
        Map.entry(SupportErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT),
        Map.entry(SupportErrorCode.OPTIMISTIC_LOCKING_CONFLICT, HttpStatus.CONFLICT),
        Map.entry(SupportErrorCode.CASE_RESOLVED, HttpStatus.CONFLICT),
        Map.entry(SupportErrorCode.REOPEN_NOT_ALLOWED, HttpStatus.CONFLICT),
        Map.entry(SupportErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST),
        Map.entry(SupportErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)
    );

    @ExceptionHandler(SupportDomainException.class)
    public Mono<ResponseEntity<ApiError>> handleSupportDomainException(SupportDomainException ex, ServerWebExchange exchange) {
        var status = SUPPORT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(PaymentDomainException.class)
    public Mono<ResponseEntity<ApiError>> handlePaymentDomainException(PaymentDomainException ex, ServerWebExchange exchange) {
        var status = PAYMENT_ERROR_STATUS_MAP.getOrDefault(ex.getErrorCode(), HttpStatus.BAD_REQUEST);
        return domainError(status, ex.getErrorCode().name(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiError>> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        var validationErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError.ValidationError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        log.warn("Validation failed for {}: {} validation errors", exchange.getRequest().getPath(), validationErrors.size());
        return respond(ApiErrors.of(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Validation failed", exchange)
            .withValidationErrors(validationErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Mono<ResponseEntity<ApiError>> handleConstraintViolation(ConstraintViolationException ex, ServerWebExchange exchange) {
        var validationErrors = ex.getConstraintViolations().stream()
            .map(cv -> new ApiError.ValidationError(cv.getPropertyPath().toString(), cv.getMessage()))
            .toList();
        log.warn("Constraint violation for {}: {} errors", exchange.getRequest().getPath(), validationErrors.size());
        return respond(ApiErrors.of(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Validation failed", exchange)
            .withValidationErrors(validationErrors));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ApiError>> handleInvalidInput(ServerWebInputException ex, ServerWebExchange exchange) {
        var parameter = ex.getMethodParameter();
        var parameterName = parameter != null ? parameter.getParameterName() : null;
        String code;
        String message;
        if (ex instanceof MissingRequestValueException missing) {
            code = "MISSING_PARAMETER";
            message = "Missing required " + missing.getLabel() + " '" + missing.getName() + "'";
        } else if (ex.getCause() instanceof TypeMismatchException && parameterName != null) {
            code = "INVALID_PARAMETER";
            message = "Invalid value for parameter '" + parameterName + "'";
        } else {
            code = "MALFORMED_REQUEST";
            message = "Malformed request body";
        }
        log.warn("Invalid input for {}: {}", exchange.getRequest().getPath(), ex.getMessage());
        return respond(ApiErrors.of(HttpStatus.BAD_REQUEST, code, message, exchange));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiError>> handleResponseStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        var status = ex.getStatusCode();
        var code = switch (status.value()) {
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> ex instanceof NoResourceFoundException ? "ROUTE_NOT_FOUND" : "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? INTERNAL_ERROR : "REQUEST_ERROR";
        };
        String message;
        if (status.is5xxServerError()) {
            message = UNEXPECTED_ERROR_MESSAGE;
        } else if (ex instanceof NoResourceFoundException) {
            message = "No endpoint " + exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value();
        } else {
            message = ex.getReason() != null ? ex.getReason() : status.toString();
        }
        logStatus(status, ex.getMessage(), ex);
        return respond(ApiErrors.of(status, code, message, exchange));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ApiError>> handleAccessDenied(AccessDeniedException ex, ServerWebExchange exchange) {
        log.warn("Access denied for {}", exchange.getRequest().getPath());
        return respond(ApiErrors.of(HttpStatus.FORBIDDEN, ACCESS_DENIED, "Access denied", exchange));
    }

    @ExceptionHandler(AuthenticationException.class)
    public Mono<ResponseEntity<ApiError>> handleAuthentication(AuthenticationException ex, ServerWebExchange exchange) {
        log.warn("Authentication failed for {}", exchange.getRequest().getPath());
        return respond(ApiErrors.of(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, "Authentication required", exchange));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public Mono<ResponseEntity<ApiError>> handleOptimisticLocking(OptimisticLockingFailureException ex, ServerWebExchange exchange) {
        log.warn("Concurrent modification for {}: {}", exchange.getRequest().getPath(), ex.getMessage());
        return respond(ApiErrors.of(HttpStatus.CONFLICT, "OPTIMISTIC_LOCKING_CONFLICT",
            "The resource was modified concurrently. Reload and retry.", exchange));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Mono<ResponseEntity<ApiError>> handleDataIntegrity(DataIntegrityViolationException ex, ServerWebExchange exchange) {
        log.warn("Data integrity violation for {}: {}", exchange.getRequest().getPath(), ex.getMessage());
        return respond(ApiErrors.of(HttpStatus.CONFLICT, "DATA_CONFLICT",
            "The request conflicts with existing data", exchange));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiError>> handleUnknown(Exception ex, ServerWebExchange exchange) {
        log.error("Unexpected error processing {}: {}", exchange.getRequest().getPath(), ex.getMessage(), ex);
        return respond(ApiErrors.of(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, UNEXPECTED_ERROR_MESSAGE, exchange));
    }

    private Mono<ResponseEntity<ApiError>> domainError(HttpStatus status, String code, String message,
                                                              ServerWebExchange exchange) {
        logStatus(status, message, null);
        var safeMessage = status.is5xxServerError() ? UNEXPECTED_ERROR_MESSAGE : message;
        return respond(ApiErrors.of(status, code, safeMessage, exchange));
    }

    private Mono<ResponseEntity<ApiError>> respond(ApiError apiError) {
        meterRegistry.counter("dsports.api.errors", "code", apiError.code(),
            "status", String.valueOf(apiError.status())).increment();
        return Mono.just(ResponseEntity.status(apiError.status()).body(apiError));
    }

    private static void logStatus(HttpStatusCode status, String message, Throwable cause) {
        if (status.is5xxServerError()) {
            log.error("{}: {}", status.value(), message, cause);
        } else {
            log.warn("{}: {}", status.value(), message);
        }
    }
}
