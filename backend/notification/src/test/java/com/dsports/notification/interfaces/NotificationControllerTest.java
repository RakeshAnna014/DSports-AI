package com.dsports.notification.interfaces;

import com.dsports.notification.application.query.GetNotificationHistoryQuery;
import com.dsports.notification.application.query.GetNotificationQuery;
import com.dsports.notification.application.result.NotificationHistoryResult;
import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.application.usecase.GetNotificationUseCase;
import com.dsports.notification.application.usecase.NotificationHistoryUseCase;
import com.dsports.notification.application.usecase.RetryNotificationUseCase;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationStatus;
import com.dsports.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationControllerTest {

    private GetNotificationUseCase getUseCase;
    private NotificationHistoryUseCase historyUseCase;
    private RetryNotificationUseCase retryUseCase;
    private NotificationController controller;
    private UUID userId;
    private NotificationResult sampleResult;

    @BeforeEach
    void setUp() {
        getUseCase = mock(GetNotificationUseCase.class);
        historyUseCase = mock(NotificationHistoryUseCase.class);
        retryUseCase = mock(RetryNotificationUseCase.class);
        controller = new NotificationController(getUseCase, historyUseCase, retryUseCase);
        userId = UUID.randomUUID();
        sampleResult = new NotificationResult(
                UUID.randomUUID(), userId, NotificationType.ORDER_PLACED,
                NotificationChannel.IN_APP, "Test message", "Subject", null,
                NotificationStatus.SENT, null, null, null,
                0, 3, Instant.now(), Instant.now(), Instant.now(), 0);
    }

    @Test
    void shouldGetHistory() {
        var history = new NotificationHistoryResult(List.of(sampleResult), 1, 0, 20);
        when(historyUseCase.execute(any(GetNotificationHistoryQuery.class))).thenReturn(Mono.just(history));

        var auth = userAuth(userId);

        StepVerifier.create(controller.getHistory(auth, 0, 20))
                .expectNext(history)
                .verifyComplete();
    }

    @Test
    void shouldGetById() {
        when(getUseCase.execute(any(GetNotificationQuery.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.getById(auth, sampleResult.id()))
                .expectNextMatches(r -> r.getStatusCode().is2xxSuccessful())
                .verifyComplete();
    }

    @Test
    void shouldReturnForbiddenForNonAdminRetry() {
        var auth = userAuth(userId);

        StepVerifier.create(controller.retry(auth, UUID.randomUUID()))
                .expectError()
                .verify();
    }

    private Authentication userAuth(UUID uid) {
        return new Authentication() {
            @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
            @Override public Object getCredentials() { return null; }
            @Override public Object getDetails() { return null; }
            @Override public Object getPrincipal() { return uid.toString(); }
            @Override public boolean isAuthenticated() { return true; }
            @Override public void setAuthenticated(boolean isAuthenticated) {}
            @Override public String getName() { return uid.toString(); }
        };
    }
}
