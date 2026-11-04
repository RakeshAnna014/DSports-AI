package com.dsports.support.domain.exception;

public class SupportDomainException extends RuntimeException {

    private final SupportErrorCode errorCode;

    public SupportDomainException(SupportErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SupportErrorCode getErrorCode() {
        return errorCode;
    }

    public static SupportDomainException notFound() {
        return new SupportDomainException(SupportErrorCode.CASE_NOT_FOUND, "Support case not found");
    }
}
