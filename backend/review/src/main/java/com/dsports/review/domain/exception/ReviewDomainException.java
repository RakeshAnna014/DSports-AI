package com.dsports.review.domain.exception;

import java.util.Collections;
import java.util.Map;

public class ReviewDomainException extends RuntimeException {

    private final ReviewErrorCode errorCode;
    private final Map<String, Object> context;

    public ReviewDomainException(ReviewErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public ReviewDomainException(ReviewErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context != null
            ? Collections.unmodifiableMap(context)
            : Collections.emptyMap();
    }

    public ReviewErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
