package com.dsports.notification.infrastructure.persistence.repository;

import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationId;
import com.dsports.notification.domain.model.NotificationType;
import com.dsports.notification.infrastructure.persistence.mapper.NotificationEntityMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class NotificationR2dbcRepositoryAdapter implements NotificationRepository {
    private final SpringR2dbcNotificationRepository springRepository;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator transactionalOperator;

    public NotificationR2dbcRepositoryAdapter(SpringR2dbcNotificationRepository springRepository,
                                               EventPublisher eventPublisher,
                                               ReactiveTransactionManager transactionManager) {
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public Mono<Notification> findById(NotificationId id) {
        return springRepository.findById(id.value())
                .map(NotificationEntityMapper::toDomain);
    }

    @Override
    public Mono<Notification> findByUserIdAndTypeAndReferenceId(UUID userId, NotificationType type, String referenceId) {
        return springRepository.findByUserIdAndTypeAndReferenceId(userId, type.name(), referenceId)
                .map(NotificationEntityMapper::toDomain);
    }

    @Override
    public Flux<Notification> findByUserId(UUID userId) {
        return springRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .map(NotificationEntityMapper::toDomain);
    }

    @Override
    public Flux<Notification> findAll(int page, int size) {
        var offset = page * size;
        return springRepository.findAllPaged(offset, size)
                .map(NotificationEntityMapper::toDomain);
    }

    @Override
    public Mono<Long> countByUserId(UUID userId) {
        return springRepository.countByUserId(userId);
    }

    @Override
    public Mono<Long> countAll() {
        return springRepository.count();
    }

    @Override
    public Mono<Long> countUnreadByUserId(UUID userId) {
        return springRepository.countUnreadByUserIdSince(userId, java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
    }

    @Override
    public Mono<Void> save(Notification notification) {
        var entity = NotificationEntityMapper.toEntity(notification);
        var id = notification.getId().value();

        return transactionalOperator.execute(tx ->
            springRepository.save(entity)
                    .onErrorMap(OptimisticLockingFailureException.class, e ->
                            new NotificationDomainException(NotificationErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                    "Concurrent modification detected for notification " + id))
                    .map(savedEntity -> {
                        notification.setVersion(savedEntity.getVersion());
                        return savedEntity;
                    })
                    .then()
        ).then(Mono.fromRunnable(() -> publishEvents(notification)));
    }

    private void publishEvents(Notification notification) {
        if (!notification.getDomainEvents().isEmpty()) {
            notification.getDomainEvents().forEach(eventPublisher::publish);
            notification.clearDomainEvents();
        }
    }
}
