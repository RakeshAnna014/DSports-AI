package com.dsports.wishlist.domain.exception;

import java.util.Collections;
import java.util.Map;

public class WishlistDomainException extends RuntimeException {
    private final WishlistErrorCode errorCode;
    private final Map<String, Object> context;

    public WishlistDomainException(WishlistErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = Collections.emptyMap();
    }

    public WishlistDomainException(WishlistErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context != null ? Collections.unmodifiableMap(context) : Collections.emptyMap();
    }

    public WishlistErrorCode getErrorCode() { return errorCode; }
    public Map<String, Object> getContext() { return context; }
}
