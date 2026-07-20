package com.dsports.pricing.domain.exception;

import java.util.Collections;
import java.util.Map;

public class PricingDomainException extends RuntimeException {

    private final PricingErrorCode errorCode;
    private final Map<String, Object> context;

    public PricingDomainException(PricingErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public PricingDomainException(PricingErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }

    public PricingErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
