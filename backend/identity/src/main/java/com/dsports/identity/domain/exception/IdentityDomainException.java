package com.dsports.identity.domain.exception;

import java.util.Collections;
import java.util.Map;

public class IdentityDomainException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> context;

    public IdentityDomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public IdentityDomainException(ErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }

    public IdentityDomainException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.GENERIC;
        this.context = Collections.emptyMap();
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
