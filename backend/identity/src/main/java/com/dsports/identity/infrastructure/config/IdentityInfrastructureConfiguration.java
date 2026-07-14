package com.dsports.identity.infrastructure.config;

import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.NotificationGateway;
import com.dsports.identity.application.port.OAuthProviderGateway;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.application.port.RefreshTokenHasher;
import com.dsports.identity.application.port.TokenProvider;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.usecase.LoginUseCase;
import com.dsports.identity.application.usecase.LogoutUseCase;
import com.dsports.identity.application.usecase.RefreshTokenUseCase;
import com.dsports.identity.application.usecase.RegisterUserUseCase;
import com.dsports.identity.infrastructure.event.SpringEventPublisherAdapter;
import com.dsports.identity.infrastructure.notification.NotificationGatewayStub;
import com.dsports.identity.infrastructure.oauth.OAuthProviderGatewayStub;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
import com.dsports.identity.infrastructure.persistence.repository.RefreshTokenR2dbcRepositoryAdapter;
import com.dsports.identity.infrastructure.persistence.repository.UserR2dbcRepositoryAdapter;
import com.dsports.identity.infrastructure.security.BCryptPasswordEncoderAdapter;
import com.dsports.identity.infrastructure.security.JwtTokenProvider;
import com.dsports.identity.infrastructure.security.Sha256RefreshTokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;

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

    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration:15m}") Duration accessTokenExpiration,
            @Value("${app.jwt.refresh-token-expiration:7d}") Duration refreshTokenExpiration) {
        return new JwtTokenProvider(secret, accessTokenExpiration, refreshTokenExpiration);
    }

    @Bean
    public RefreshTokenHasher refreshTokenHasher() {
        return new Sha256RefreshTokenHasher();
    }

    @Bean
    public RefreshTokenRepository refreshTokenRepository(DatabaseClient databaseClient) {
        return new RefreshTokenR2dbcRepositoryAdapter(databaseClient);
    }

    @Bean
    public LoginUseCase loginUseCase(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenProvider tokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher) {
        return new LoginUseCase(userRepository, passwordEncoder, tokenProvider, refreshTokenRepository, refreshTokenHasher);
    }

    @Bean
    public RefreshTokenUseCase refreshTokenUseCase(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            TokenProvider tokenProvider,
            RefreshTokenHasher refreshTokenHasher) {
        return new RefreshTokenUseCase(refreshTokenRepository, userRepository, tokenProvider, refreshTokenHasher);
    }

    @Bean
    public LogoutUseCase logoutUseCase(RefreshTokenRepository refreshTokenRepository, RefreshTokenHasher refreshTokenHasher) {
        return new LogoutUseCase(refreshTokenRepository, refreshTokenHasher);
    }
}
