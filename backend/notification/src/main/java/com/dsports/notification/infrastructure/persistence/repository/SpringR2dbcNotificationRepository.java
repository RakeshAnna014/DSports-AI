package com.dsports.notification.infrastructure.persistence.repository;

import com.dsports.notification.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcNotificationRepository extends ReactiveCrudRepository<NotificationEntity, UUID> {
    Flux<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Mono<NotificationEntity> findByUserIdAndTypeAndReferenceId(UUID userId, String type, String referenceId);

    @Query("SELECT * FROM notifications ORDER BY created_at DESC OFFSET :offset LIMIT :limit")
    Flux<NotificationEntity> findAllPaged(int offset, int limit);

    Mono<Long> countByUserId(UUID userId);

    @Query("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND status = 'SENT' AND sent_at > :since")
    Mono<Long> countUnreadByUserIdSince(UUID userId, java.time.Instant since);
}
