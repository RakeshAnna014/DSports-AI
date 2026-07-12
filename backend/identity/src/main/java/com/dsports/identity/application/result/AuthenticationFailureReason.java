package com.dsports.identity.application.result;

public enum AuthenticationFailureReason {
    USER_NOT_FOUND,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    ACCOUNT_DELETED,
    INVALID_PASSWORD
}
