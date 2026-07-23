package com.dsports.order.domain.checkout.exception;

public class CheckoutDomainException extends RuntimeException {
    private final CheckoutErrorCode errorCode;

    public CheckoutDomainException(CheckoutErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CheckoutErrorCode getErrorCode() { return errorCode; }
}
