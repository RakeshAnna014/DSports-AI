package com.dsports.support.domain.exception;

public enum SupportErrorCode {
    /** Also returned when the case exists but belongs to someone else: the two are deliberately indistinguishable. */
    CASE_NOT_FOUND,
    INVALID_STATUS_TRANSITION,
    VALIDATION_ERROR,
    IDEMPOTENCY_KEY_REUSED,
    RELATED_RECORD_NOT_FOUND,
    REOPEN_NOT_ALLOWED,
    CASE_RESOLVED,
    OPTIMISTIC_LOCKING_CONFLICT,
    INTERNAL_ERROR
}
