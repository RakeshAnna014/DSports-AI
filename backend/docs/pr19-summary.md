# PR #19 — Notification Management

## Architecture

The notification module follows Clean Architecture / Domain-Driven Design with four layers:

```
interfaces (controller)
    ↓
application (use cases, ports)
    ↓
domain (entity, value objects, enums)
    ↓
infrastructure (persistence, providers, event handlers)
```

The module is provider-independent: `NotificationProvider` interface abstracts Email, SMS, Push, and In-App channels. Mock implementations ship with the module; real providers (SMTP, Twilio, Firebase) can be swapped in via configuration.

## Notification Flow

```
Domain Event (OrderPlaced, PaymentSucceeded, etc.)
    ↓
DomainEventNotificationHandler (@EventListener)
    ↓
CreateNotificationUseCase
    ↓
NotificationRepository.save() + NotificationProvider.send()
    ↓
Notification persisted with PENDING → SENT / FAILED status
```

**In-App notifications** skip the provider and are created directly in `SENT` status (or via the same flow using a no-op in-app provider).

## Supported Channels

| Channel | Current Provider | Future Provider |
|---------|-----------------|-----------------|
| EMAIL   | MockEmailProvider (Console log + simulated success) | Spring Mail / SMTP |
| SMS     | MockSmsProvider (Console log + simulated success)   | Twilio API |
| PUSH    | MockPushProvider (Console log + simulated success)  | Firebase Cloud Messaging |
| IN_APP  | No provider needed — stored directly in DB | N/A |

## Retry Strategy

- Failed notifications can be retried up to `maxRetries` (configurable, default 3).
- Retry flow: `FAILED → RETRYING → SENT` or `RETRYING → FAILED`.
- Only admin users can trigger retries via `POST /api/v1/notifications/{id}/retry`.
- The domain entity tracks `retryCount` and `maxRetries` to prevent infinite retries.

## Database Changes

**Flyway Migration:** `V26__create_notification_tables.sql`

- `notifications` table — stores all notification records
- `notification_templates` table — stores template definitions for future template-based rendering
- Indexes on `user_id`, `status`, `type`, `created_at`

## API Endpoints

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| GET | `/api/v1/notifications` | Paginated notification history | Authenticated (own) / Admin (all) |
| GET | `/api/v1/notifications/{id}` | Single notification detail | Authenticated (own) / Admin (any) |
| POST | `/api/v1/notifications/{id}/retry` | Retry a failed notification | Admin only |

## Future SMTP Integration

To replace `MockEmailProvider` with real SMTP:

```java
@Component
public class SmtpEmailProvider implements NotificationProvider {
    private final JavaMailSender mailSender;

    @Override
    public Mono<ProviderResult> send(Notification notification) {
        return Mono.fromCallable(() -> {
            var message = mailSender.createMimeMessage();
            message.setSubject(notification.getSubject());
            message.setText(notification.getMessage().value());
            mailSender.send(message);
            return new ProviderResult(true, "Email sent", null);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
```

Register in `NotificationInfrastructureConfiguration`:

```java
@Bean
@Primary
public NotificationProvider emailProvider() {
    return new SmtpEmailProvider();
}
```

## Future Twilio Integration

```java
@Component
public class TwilioSmsProvider implements NotificationProvider {
    @Override
    public Mono<ProviderResult> send(Notification notification) {
        return Mono.fromCallable(() -> {
            var message = Message.creator(
                new PhoneNumber(to),
                new PhoneNumber(from),
                notification.getMessage().value()
            ).create();
            return new ProviderResult(true, "SMS sent", message.getSid());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
```

## Future Firebase Push Integration

```java
@Component
public class FirebasePushProvider implements NotificationProvider {
    @Override
    public Mono<ProviderResult> send(Notification notification) {
        return Mono.fromCallable(() -> {
            var message = Message.builder()
                .setNotification(com.google.firebase.messaging.Notification.builder()
                    .setTitle(notification.getSubject())
                    .setBody(notification.getMessage().value())
                    .build())
                .setToken(deviceToken)
                .build();
            var response = FirebaseMessaging.getInstance().send(message);
            return new ProviderResult(true, "Push sent", response);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
```

## Frontend Summary

- **NotificationCenter** — Dropdown in the header bar showing latest 5 notifications with unread badge
- **NotificationHistoryPage** — `/notifications` — paginated table with status chips, retry button (admin)
- **NotificationDetailPage** — `/notifications/{id}` — full detail view with retry action (admin)
- **notificationStore** — Zustand store for fetching history and retrying notifications

## Testing Summary

| Layer | Tests |
|-------|-------|
| Domain | `NotificationTest` — status transitions, retry logic, validation |
| Application | `CreateNotificationUseCaseTest` — creation, provider success/failure, validation |
| Application | `RetryNotificationUseCaseTest` — retry flow, error cases |
| Infrastructure | `MockEmailProviderTest`, `MockSmsProviderTest`, `MockPushProviderTest` — send, retry, status |
| Presentation | `NotificationControllerTest` — history, get by id, admin retry enforcement |

## Production Readiness

- [x] Reactive (no blocking code)
- [x] Optimistic locking with HTTP 409 on conflict
- [x] Authenticated users can only access their own notifications
- [x] Admin users may access all notifications and trigger retries
- [x] Swagger group (`notifications`) with full endpoint documentation
- [x] Flyway migration with proper constraints and indexes
- [x] Global exception handling mapped to correct HTTP status codes
- [x] All domain events handled: OrderPlaced, PaymentSucceeded, PaymentFailed, OrderConfirmed, OrderShipped, OrderDelivered, OrderCancelled, RefundCompleted
- [x] Provider-independent design — swap mock for real providers via DI
- [x] Retry strategy with configurable limits
