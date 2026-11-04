package com.dsports.shared.application.outbox;

/** Outbox message types shared between the modules that record them and the dispatcher that handles them. */
public final class OutboxMessageTypes {

    public static final String NOTIFY_ORDER_PLACED = "NOTIFY_ORDER_PLACED";
    public static final String NOTIFY_ORDER_CONFIRMED = "NOTIFY_ORDER_CONFIRMED";
    public static final String NOTIFY_ORDER_CANCELLED = "NOTIFY_ORDER_CANCELLED";
    public static final String NOTIFY_ORDER_SHIPPED = "NOTIFY_ORDER_SHIPPED";
    public static final String NOTIFY_ORDER_DELIVERED = "NOTIFY_ORDER_DELIVERED";
    public static final String NOTIFY_PAYMENT_SUCCEEDED = "NOTIFY_PAYMENT_SUCCEEDED";
    public static final String NOTIFY_PAYMENT_FAILED = "NOTIFY_PAYMENT_FAILED";
    public static final String NOTIFY_REFUND_COMPLETED = "NOTIFY_REFUND_COMPLETED";
    public static final String CREATE_SHIPMENT = "CREATE_SHIPMENT";
    public static final String VOID_PAYMENT_AUTHORIZATION = "VOID_PAYMENT_AUTHORIZATION";

    private OutboxMessageTypes() {
    }
}
