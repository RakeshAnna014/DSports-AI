package com.dsports.notification.infrastructure.config;

import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.application.usecase.CreateNotificationUseCase;
import com.dsports.notification.application.usecase.GetNotificationUseCase;
import com.dsports.notification.application.usecase.NotificationHistoryUseCase;
import com.dsports.notification.application.usecase.RetryNotificationUseCase;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.infrastructure.event.NotificationSpringEventPublisherAdapter;
import com.dsports.notification.infrastructure.persistence.repository.NotificationR2dbcRepositoryAdapter;
import com.dsports.notification.infrastructure.persistence.repository.SpringR2dbcNotificationRepository;
import com.dsports.notification.infrastructure.provider.MockEmailProvider;
import com.dsports.notification.infrastructure.provider.MockPushProvider;
import com.dsports.notification.infrastructure.provider.MockSmsProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;

import java.util.Map;

@Configuration
public class NotificationInfrastructureConfiguration {

    private final SpringR2dbcNotificationRepository springRepository;
    private final ReactiveTransactionManager transactionManager;

    public NotificationInfrastructureConfiguration(SpringR2dbcNotificationRepository springRepository,
                                                     ReactiveTransactionManager transactionManager) {
        this.springRepository = springRepository;
        this.transactionManager = transactionManager;
    }

    @Bean
    public EventPublisher notificationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new NotificationSpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public NotificationRepository notificationRepository(EventPublisher notificationEventPublisher) {
        return new NotificationR2dbcRepositoryAdapter(springRepository, notificationEventPublisher, transactionManager);
    }

    @Bean
    public NotificationProvider mockEmailProvider() {
        return new MockEmailProvider();
    }

    @Bean
    public NotificationProvider mockSmsProvider() {
        return new MockSmsProvider();
    }

    @Bean
    public NotificationProvider mockPushProvider() {
        return new MockPushProvider();
    }

    @Bean
    public Map<NotificationChannel, NotificationProvider> notificationProviders(
            NotificationProvider mockEmailProvider,
            NotificationProvider mockSmsProvider,
            NotificationProvider mockPushProvider) {
        return Map.of(
                NotificationChannel.EMAIL, mockEmailProvider,
                NotificationChannel.SMS, mockSmsProvider,
                NotificationChannel.PUSH, mockPushProvider
        );
    }

    @Bean
    public CreateNotificationUseCase createNotificationUseCase(
            NotificationRepository notificationRepository,
            Map<NotificationChannel, NotificationProvider> notificationProviders,
            EventPublisher notificationEventPublisher) {
        return new CreateNotificationUseCase(notificationRepository, notificationProviders, notificationEventPublisher);
    }

    @Bean
    public RetryNotificationUseCase retryNotificationUseCase(
            NotificationRepository notificationRepository,
            Map<NotificationChannel, NotificationProvider> notificationProviders,
            EventPublisher notificationEventPublisher) {
        return new RetryNotificationUseCase(notificationRepository, notificationProviders, notificationEventPublisher);
    }

    @Bean
    public GetNotificationUseCase getNotificationUseCase(
            NotificationRepository notificationRepository) {
        return new GetNotificationUseCase(notificationRepository);
    }

    @Bean
    public NotificationHistoryUseCase notificationHistoryUseCase(
            NotificationRepository notificationRepository) {
        return new NotificationHistoryUseCase(notificationRepository);
    }
}
