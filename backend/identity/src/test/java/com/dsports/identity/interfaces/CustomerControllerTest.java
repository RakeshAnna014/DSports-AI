package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.UpdateCustomerProfileCommand;
import com.dsports.identity.application.usecase.GetCustomerProfileUseCase;
import com.dsports.identity.application.usecase.UpdateCustomerProfileUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.CustomerProfileResponse;
import com.dsports.identity.interfaces.dto.UpdateCustomerProfileRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerControllerTest {

    private final GetCustomerProfileUseCase getCustomerProfileUseCase = mock();
    private final UpdateCustomerProfileUseCase updateCustomerProfileUseCase = mock();
    private final CustomerController controller = new CustomerController(
            getCustomerProfileUseCase, updateCustomerProfileUseCase);

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void getProfileShouldReturnOkWithProfileData() {
        var result = new com.dsports.identity.application.result.CustomerProfileResult(
                USER_ID, "user@example.com", "John", "Doe",
                "+919876543210", "https://example.com/avatar.jpg",
                LocalDate.of(1990, 5, 15), List.of("CUSTOMER"));

        when(getCustomerProfileUseCase.execute(any(UserId.class)))
                .thenReturn(Mono.just(result));

        StepVerifier.create(controller.getProfile(createAuth(USER_ID)))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                    var body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.userId()).isEqualTo(USER_ID);
                    assertThat(body.email()).isEqualTo("user@example.com");
                    assertThat(body.firstName()).isEqualTo("John");
                    assertThat(body.lastName()).isEqualTo("Doe");
                    assertThat(body.phoneNumber()).isEqualTo("+919876543210");
                    assertThat(body.profileImageUrl()).isEqualTo("https://example.com/avatar.jpg");
                    assertThat(body.dateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 15));
                    assertThat(body.roles()).contains("CUSTOMER");
                })
                .verifyComplete();
    }

    @Test
    void updateProfileShouldReturnOkWithUpdatedData() {
        var updatedResult = new com.dsports.identity.application.result.CustomerProfileResult(
                USER_ID, "user@example.com", "Jane", "Smith",
                "+919876543210", null, null, List.of("CUSTOMER"));

        when(updateCustomerProfileUseCase.execute(any(UpdateCustomerProfileCommand.class)))
                .thenReturn(Mono.just(updatedResult));

        var request = new UpdateCustomerProfileRequest(
                "Jane", "Smith", "+919876543210", null, null);

        StepVerifier.create(controller.updateProfile(request, createAuth(USER_ID)))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                    var body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.firstName()).isEqualTo("Jane");
                    assertThat(body.lastName()).isEqualTo("Smith");
                    assertThat(body.phoneNumber()).isEqualTo("+919876543210");
                })
                .verifyComplete();
    }

    private org.springframework.security.core.Authentication createAuth(UUID userId) {
        var auth = mock(org.springframework.security.core.Authentication.class);
        when(auth.getPrincipal()).thenReturn(userId.toString());
        return auth;
    }
}
