package com.dsports.ground.domain.model;

/** Who cancelled a booking. The cancellation policy depends on it: an owner cancellation is always refunded in full. */
public enum CancelledBy {
    CUSTOMER, OWNER
}
