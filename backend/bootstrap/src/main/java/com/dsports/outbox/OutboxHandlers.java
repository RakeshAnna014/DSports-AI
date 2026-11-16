package com.dsports.outbox;

import com.dsports.notification.application.command.CreateNotificationCommand;
import com.dsports.notification.application.usecase.CreateNotificationUseCase;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationType;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.refund.ProcessRefundUseCase;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shipment.application.command.CreateShipmentCommand;
import com.dsports.shipment.application.usecase.CreateShipmentUseCase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Executes outbox work. Every handler is idempotent because a message can be delivered more than once
 * (retry after a failure, or after a worker crashed mid-way): notifications are unique per user, type and
 * reference; an order has at most one shipment; voiding an authorisation twice is harmless.
 */
@Component
public class OutboxHandlers {

    private final CreateNotificationUseCase createNotificationUseCase;
    private final CreateShipmentUseCase createShipmentUseCase;
    private final PaymentGateway paymentGateway;
    private final ProcessRefundUseCase processRefundUseCase;

    public OutboxHandlers(CreateNotificationUseCase createNotificationUseCase,
                          CreateShipmentUseCase createShipmentUseCase,
                          PaymentGateway paymentGateway,
                          ProcessRefundUseCase processRefundUseCase) {
        this.createNotificationUseCase = createNotificationUseCase;
        this.createShipmentUseCase = createShipmentUseCase;
        this.paymentGateway = paymentGateway;
        this.processRefundUseCase = processRefundUseCase;
    }

    public Mono<Void> handle(String type, String aggregateId, Map<String, String> payload) {
        return switch (type) {
            case OutboxMessageTypes.CREATE_SHIPMENT ->
                createShipmentUseCase.execute(new CreateShipmentCommand(UUID.fromString(aggregateId))).then();
            case OutboxMessageTypes.VOID_PAYMENT_AUTHORIZATION -> paymentGateway.cancelPayment(payload.get("gatewayReference"))
                .flatMap(result -> result.success()
                    ? Mono.<Void>empty()
                    : Mono.<Void>error(new IllegalStateException("Gateway refused void: " + result.message())));
            case OutboxMessageTypes.PROCESS_REFUND -> processRefundUseCase.execute(UUID.fromString(aggregateId)).then();
            case OutboxMessageTypes.NOTIFY_BOOKING_CONFIRMED -> notify(payload, aggregateId, "BOOKING",
                NotificationType.BOOKING_CONFIRMED, "Booking Confirmed - " + payload.get("bookingNumber"),
                "Your booking " + payload.get("bookingNumber") + " at " + payload.get("groundName") + " on " + payload.get("when") + " is confirmed.");
            case OutboxMessageTypes.NOTIFY_BOOKING_CANCELLED -> notify(payload, aggregateId, "BOOKING",
                NotificationType.BOOKING_CANCELLED, "Booking Cancelled - " + payload.get("bookingNumber"),
                "Your booking " + payload.get("bookingNumber") + " at " + payload.get("groundName") + " on " + payload.get("when") + " has been cancelled. "
                    + payload.getOrDefault("note", ""));
            case OutboxMessageTypes.NOTIFY_ORDER_PLACED -> notify(payload, aggregateId, "ORDER",
                NotificationType.ORDER_PLACED, "Order Placed - " + payload.get("orderNumber"),
                "Your order " + payload.get("orderNumber") + " has been placed. Total: "
                    + payload.get("currency") + " " + payload.get("grandTotal"));
            case OutboxMessageTypes.NOTIFY_ORDER_CONFIRMED -> notify(payload, aggregateId, "ORDER",
                NotificationType.ORDER_CONFIRMED, "Order Confirmed - " + payload.get("orderNumber"),
                "Your order " + payload.get("orderNumber") + " is confirmed and being prepared for shipment.");
            case OutboxMessageTypes.NOTIFY_ORDER_CANCELLED -> notify(payload, aggregateId, "ORDER",
                NotificationType.ORDER_CANCELLED, "Order Cancelled - " + payload.get("orderNumber"),
                "Your order " + payload.get("orderNumber") + " has been cancelled.");
            case OutboxMessageTypes.NOTIFY_ORDER_SHIPPED -> notify(payload, aggregateId, "ORDER",
                NotificationType.ORDER_SHIPPED, "Order Shipped - " + payload.get("orderNumber"),
                "Your order " + payload.get("orderNumber") + " has been shipped.");
            case OutboxMessageTypes.NOTIFY_ORDER_DELIVERED -> notify(payload, aggregateId, "ORDER",
                NotificationType.ORDER_DELIVERED, "Order Delivered - " + payload.get("orderNumber"),
                "Your order " + payload.get("orderNumber") + " has been delivered.");
            case OutboxMessageTypes.NOTIFY_PAYMENT_SUCCEEDED -> notify(payload, aggregateId, "PAYMENT",
                NotificationType.PAYMENT_SUCCESS, "Payment Successful",
                "We received your payment of " + payload.get("currency") + " " + payload.get("amount")
                    + " (reference " + payload.get("paymentReference") + ").");
            case OutboxMessageTypes.NOTIFY_PAYMENT_FAILED -> notify(payload, aggregateId, "PAYMENT",
                NotificationType.PAYMENT_FAILED, "Payment Failed",
                "Your payment could not be completed: " + payload.get("reason") + ". You can try again.");
            case OutboxMessageTypes.NOTIFY_REFUND_COMPLETED -> notify(payload, aggregateId, "REFUND",
                NotificationType.REFUND_COMPLETED, "Refund Completed",
                "A refund of " + payload.get("currency") + " " + payload.get("amount") + " has been issued.");
            default -> Mono.error(new IllegalArgumentException("No outbox handler for type " + type));
        };
    }

    private Mono<Void> notify(Map<String, String> payload, String referenceId, String referenceType,
                              NotificationType type, String subject, String message) {
        var command = new CreateNotificationCommand(UUID.fromString(payload.get("userId")), type,
            NotificationChannel.IN_APP, message, subject, referenceId, referenceType);
        return createNotificationUseCase.execute(command).then();
    }
}
