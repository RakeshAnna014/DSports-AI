package com.dsports.shipment.domain.exception;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ShipmentDomainException extends RuntimeException {
    private final ShipmentErrorCode errorCode;
    private final Map<String, Object> context;

    public ShipmentDomainException(ShipmentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = new HashMap<>();
    }

    public ShipmentDomainException(ShipmentErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context != null ? Collections.unmodifiableMap(new HashMap<>(context)) : new HashMap<>();
    }

    public ShipmentErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
