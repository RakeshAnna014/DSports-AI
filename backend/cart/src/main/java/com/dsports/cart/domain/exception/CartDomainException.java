package com.dsports.cart.domain.exception;

import java.util.Collections;
import java.util.Map;

public class CartDomainException extends RuntimeException {
    private final CartErrorCode errorCode;
    private final Map<String, Object> context;

    public CartDomainException(CartErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = Collections.emptyMap();
    }

    public CartDomainException(CartErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context != null ? Collections.unmodifiableMap(context) : Collections.emptyMap();
    }

    public CartErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
