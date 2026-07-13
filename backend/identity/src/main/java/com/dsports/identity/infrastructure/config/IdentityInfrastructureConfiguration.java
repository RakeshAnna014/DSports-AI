package com.dsports.identity.infrastructure.config;

import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.NotificationGateway;
import com.dsports.identity.application.port.OAuthProviderGateway;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.infrastructure.event.SpringEventPublisherAdapter;
import com.dsports.identity.infrastructure.notification.NotificationGatewayStub;
import com.dsports.identity.infrastructure.oauth.OAuthProviderGatewayStub;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
import com.dsports.identity.infrastructure.persistence.repository.UserR2dbcRepositoryAdapter;
import com.dsports.identity.infrastructure.security.BCryptPasswordEncoderAdapter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
public class IdentityInfrastructureConfiguration {

    @Bean
    public CustomerEntityMapper customerEntityMapper() {
        return new CustomerEntityMapper();
    }

    @Bean
    public UserRepository userRepository(
            DatabaseClient databaseClient,
            CustomerEntityMapper mapper) {
        return new UserR2dbcRepositoryAdapter(databaseClient, mapper);
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
