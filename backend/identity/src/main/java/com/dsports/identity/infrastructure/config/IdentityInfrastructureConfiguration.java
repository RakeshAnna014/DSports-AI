package com.dsports.identity.infrastructure.config;

import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.NotificationGateway;
import com.dsports.identity.application.port.OAuthProviderGateway;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.infrastructure.event.SpringEventPublisherAdapter;
import com.dsports.identity.infrastructure.notification.NotificationGatewayStub;
import com.dsports.identity.infrastructure.oauth.OAuthProviderGatewayStub;
import com.dsports.identity.infrastructure.persistence.adapter.UserRepositoryAdapter;
import com.dsports.identity.infrastructure.persistence.mapper.UserPersistenceMapper;
import com.dsports.identity.infrastructure.persistence.repository.UserR2dbcRepository;
import com.dsports.identity.infrastructure.security.BCryptPasswordEncoderAdapter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityInfrastructureConfiguration {

    @Bean
    public UserPersistenceMapper userPersistenceMapper() {
        return new UserPersistenceMapper();
    }

    @Bean
    public UserRepository userRepositoryAdapter(
            UserR2dbcRepository r2dbcRepository,
            UserPersistenceMapper mapper) {
        return new UserRepositoryAdapter(r2dbcRepository, mapper);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoderAdapter();
    }

    @Bean
    public EventPublisher eventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new SpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public NotificationGateway notificationGateway() {
        return new NotificationGatewayStub();
    }

    @Bean
    public OAuthProviderGateway oAuthProviderGateway() {
        return new OAuthProviderGatewayStub();
    }
}
