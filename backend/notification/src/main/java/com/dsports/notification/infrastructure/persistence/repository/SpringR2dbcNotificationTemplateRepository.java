package com.dsports.notification.infrastructure.persistence.repository;

import com.dsports.notification.infrastructure.persistence.entity.NotificationTemplateEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcNotificationTemplateRepository extends ReactiveCrudRepository<NotificationTemplateEntity, UUID> {
    Mono<NotificationTemplateEntity> findByNameAndChannel(String name, String channel);
}
