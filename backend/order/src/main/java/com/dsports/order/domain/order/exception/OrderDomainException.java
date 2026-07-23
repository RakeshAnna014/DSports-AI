package com.dsports.order.domain.order.exception;

public class OrderDomainException extends RuntimeException {
    private final OrderErrorCode errorCode;

    public OrderDomainException(OrderErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OrderErrorCode getErrorCode() { return errorCode; }
}
