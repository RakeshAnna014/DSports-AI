package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.UpdateCustomerProfileCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.domain.model.UserProfileManagementService;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateCustomerProfileUseCaseTest {

    @Mock private UserRepository userRepository;

    private UpdateCustomerProfileUseCase useCase;
    private User activeUser;
    private UserId userId;

    @BeforeEach
    void setUp() {
        var profileService = new UserProfileManagementService();
        useCase = new UpdateCustomerProfileUseCase(userRepository, profileService);
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
    void shouldUpdateAllFields() {
        when(userRepository.findById(userId)).thenReturn(Mono.just(activeUser));
        when(userRepository.save(any())).thenReturn(Mono.empty());

        var command = new UpdateCustomerProfileCommand(
                userId, "Jane", "Smith", "+919876543210",
                "https://example.com/avatar.jpg", LocalDate.of(1990, 5, 15));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.firstName()).isEqualTo("Jane");
                    assertThat(result.lastName()).isEqualTo("Smith");
                    assertThat(result.phoneNumber()).isEqualTo("+919876543210");
                    assertThat(result.profileImageUrl()).isEqualTo("https://example.com/avatar.jpg");
                    assertThat(result.dateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 15));
                    assertThat(result.email()).isEqualTo("user@example.com");
                })
                .verifyComplete();
    }

    @Test
    void shouldClearOptionalFieldsWhenNull() {
        when(userRepository.findById(userId)).thenReturn(Mono.just(activeUser));
        when(userRepository.save(any())).thenReturn(Mono.empty());

        var command = new UpdateCustomerProfileCommand(
                userId, "Jane", "Smith", null, null, null);

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.firstName()).isEqualTo("Jane");
                    assertThat(result.phoneNumber()).isNull();
                    assertThat(result.profileImageUrl()).isNull();
                    assertThat(result.dateOfBirth()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectFutureDateOfBirth() {
        var futureDate = LocalDate.now().plusDays(1);
        var command = new UpdateCustomerProfileCommand(
                userId, "Jane", "Smith", null, null, futureDate);

        StepVerifier.create(useCase.execute(command))
                .expectError(com.dsports.identity.domain.exception.IdentityDomainException.class)
                .verify();
    }

    @Test
    void shouldRejectInvalidPhone() {
        var command = new UpdateCustomerProfileCommand(
                userId, "Jane", "Smith", "invalid-phone", null, null);

        StepVerifier.create(useCase.execute(command))
                .expectError(com.dsports.identity.domain.exception.IdentityDomainException.class)
                .verify();
    }

    @Test
    void shouldRejectBlankFirstName() {
        var command = new UpdateCustomerProfileCommand(
                userId, "", "Smith", null, null, null);

        StepVerifier.create(useCase.execute(command))
                .expectError(com.dsports.identity.domain.exception.IdentityDomainException.class)
                .verify();
    }

    @Test
    void shouldPreserveEmail() {
        when(userRepository.findById(userId)).thenReturn(Mono.just(activeUser));
        when(userRepository.save(any())).thenReturn(Mono.empty());

        var command = new UpdateCustomerProfileCommand(
                userId, "Jane", "Smith", null, null, null);

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.email()).isEqualTo("user@example.com");
                })
                .verifyComplete();
    }
}
