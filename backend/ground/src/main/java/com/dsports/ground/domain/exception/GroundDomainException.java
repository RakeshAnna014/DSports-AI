package com.dsports.ground.domain.exception;

public class GroundDomainException extends RuntimeException {

    private final GroundErrorCode errorCode;

    public GroundDomainException(GroundErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GroundErrorCode getErrorCode() {
        return errorCode;
    }
}
