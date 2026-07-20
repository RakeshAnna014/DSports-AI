package com.dsports.inventory.domain.exception;

import java.util.Collections;
import java.util.Map;

public class InventoryDomainException extends RuntimeException {

    private final InventoryErrorCode errorCode;
    private final Map<String, Object> context;

    public InventoryDomainException(InventoryErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public InventoryDomainException(InventoryErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }

    public InventoryErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
