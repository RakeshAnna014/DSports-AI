package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetCustomerProfileUseCaseTest {

    @Mock private UserRepository userRepository;

    private GetCustomerProfileUseCase useCase;
    private User activeUser;
    private UserId userId;

    @BeforeEach
    void setUp() {
        useCase = new GetCustomerProfileUseCase(userRepository);
        userId = UserId.generate();
        activeUser = User.reconstitute(
                userId, Email.from("user@example.com"), "hash",
                CustomerName.of("John", "Doe"), null, null, null,
                UserStatus.ACTIVE, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                java.time.Instant.now(), java.time.Instant.now(), null,
                java.util.Collections.emptyList(), 0
        );
    }

    @Test
    void shouldReturnProfileForExistingUser() {
        when(userRepository.findById(userId)).thenReturn(Mono.just(activeUser));

        StepVerifier.create(useCase.execute(userId))
                .assertNext(result -> {
                    assertThat(result.userId()).isEqualTo(userId.value());
                    assertThat(result.email()).isEqualTo("user@example.com");
                    assertThat(result.firstName()).isEqualTo("John");
                    assertThat(result.lastName()).isEqualTo("Doe");
                    assertThat(result.roles()).contains("CUSTOMER");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorForNonexistentUser() {
        when(userRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(UserId.generate()))
                .expectErrorMatches(e ->
                        e instanceof IdentityDomainException
                                && ((IdentityDomainException) e).getErrorCode() == ErrorCode.USER_NOT_FOUND)
                .verify();
    }
}
