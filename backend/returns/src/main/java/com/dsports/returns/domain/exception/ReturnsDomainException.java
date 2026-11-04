package com.dsports.returns.domain.exception;

public class ReturnsDomainException extends RuntimeException {

    private final ReturnsErrorCode errorCode;

    public ReturnsDomainException(ReturnsErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ReturnsErrorCode getErrorCode() {
        return errorCode;
    }
}
