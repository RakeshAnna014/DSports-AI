package com.dsports.cricket.domain.exception;

public class CricketDomainException extends RuntimeException {

    private final CricketErrorCode errorCode;

    public CricketDomainException(CricketErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CricketErrorCode getErrorCode() {
        return errorCode;
    }
}
