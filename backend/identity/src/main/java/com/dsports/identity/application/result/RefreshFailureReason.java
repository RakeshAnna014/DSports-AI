package com.dsports.identity.application.result;

public enum RefreshFailureReason {
    TOKEN_NOT_FOUND,
    TOKEN_REVOKED,
    TOKEN_EXPIRED,
    USER_NOT_FOUND,
    USER_DISABLED,
    USER_DELETED,
    USER_LOCKED
}
