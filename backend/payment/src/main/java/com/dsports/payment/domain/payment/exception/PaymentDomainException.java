package com.dsports.payment.domain.payment.exception;

public class PaymentDomainException extends RuntimeException {
    private final PaymentErrorCode errorCode;

    public PaymentDomainException(PaymentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PaymentErrorCode getErrorCode() { return errorCode; }
}
