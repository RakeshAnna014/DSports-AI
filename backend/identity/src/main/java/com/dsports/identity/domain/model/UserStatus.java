package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;

import java.util.EnumSet;
import java.util.Set;

public enum UserStatus {

    REGISTERED,
    PENDING_VERIFICATION,
    ACTIVE,
    LOCKED,
    DISABLED,
    DELETED;

    private static final Set<UserStatus> ACTIVE_STATES = EnumSet.of(REGISTERED, PENDING_VERIFICATION, ACTIVE);
    private static final Set<UserStatus> LOGIN_ALLOWED = EnumSet.of(PENDING_VERIFICATION, ACTIVE);
    private static final Set<UserStatus> CAN_BE_DELETED = EnumSet.of(REGISTERED, PENDING_VERIFICATION, ACTIVE, LOCKED, DISABLED);

    public boolean canTransitionTo(UserStatus target) {
        return switch (this) {
            case REGISTERED -> target == PENDING_VERIFICATION || target == DELETED;
            case PENDING_VERIFICATION -> target == ACTIVE || target == LOCKED || target == DISABLED || target == DELETED;
            case ACTIVE -> target == LOCKED || target == DISABLED || target == DELETED;
            case LOCKED -> target == ACTIVE || target == DISABLED || target == DELETED;
            case DISABLED -> target == ACTIVE || target == DELETED;
            case DELETED -> false;
        };
    }

    public UserStatus transitionTo(UserStatus target) {
        if (!canTransitionTo(target)) {
            throw new IdentityDomainException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot transition from " + this + " to " + target);
        }
        return target;
    }

    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }

    public boolean canLogin() {
        return LOGIN_ALLOWED.contains(this);
    }

    public boolean canBeDeleted() {
        return CAN_BE_DELETED.contains(this);
    }

    public boolean isDeleted() {
        return this == DELETED;
    }

    public boolean isDisabled() {
        return this == DISABLED;
    }

    public boolean isLocked() {
        return this == LOCKED;
    }
}
