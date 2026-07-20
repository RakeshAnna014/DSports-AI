package com.dsports.catalog.domain.exception;

import java.util.Collections;
import java.util.Map;

public class CatalogDomainException extends RuntimeException {

    private final CatalogErrorCode errorCode;
    private final Map<String, Object> context;

    public CatalogDomainException(CatalogErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public CatalogDomainException(CatalogErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }

    public CatalogDomainException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = CatalogErrorCode.GENERIC;
        this.context = Collections.emptyMap();
    }

    public CatalogErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
