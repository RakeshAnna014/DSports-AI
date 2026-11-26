package com.dsports.cricket.domain.exception;

public class CricketDomainException extends RuntimeException {

    private final CricketErrorCode errorCode;
    private final transient Object details;

    public CricketDomainException(CricketErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** {@code details} is extra data for the client (for example the current score after a scoring conflict); it is shown as the error's details. */
    public CricketDomainException(CricketErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public Object getDetails() {
        return details;
    }

    public CricketErrorCode getErrorCode() {
        return errorCode;
    }
}
