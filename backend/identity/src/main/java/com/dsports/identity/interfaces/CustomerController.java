package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.UpdateCustomerProfileCommand;
import com.dsports.identity.application.usecase.GetCustomerProfileUseCase;
import com.dsports.identity.application.usecase.UpdateCustomerProfileUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.CustomerProfileResponse;
import com.dsports.identity.interfaces.dto.UpdateCustomerProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final GetCustomerProfileUseCase getCustomerProfileUseCase;
    private final UpdateCustomerProfileUseCase updateCustomerProfileUseCase;

    public CustomerController(GetCustomerProfileUseCase getCustomerProfileUseCase,
                              UpdateCustomerProfileUseCase updateCustomerProfileUseCase) {
        this.getCustomerProfileUseCase = getCustomerProfileUseCase;
        this.updateCustomerProfileUseCase = updateCustomerProfileUseCase;
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<CustomerProfileResponse>> getProfile(Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        return getCustomerProfileUseCase.execute(userId)
                .map(result -> ResponseEntity.ok(toResponse(result)));
    }

    @PutMapping("/me")
    public Mono<ResponseEntity<CustomerProfileResponse>> updateProfile(
            @Valid @RequestBody UpdateCustomerProfileRequest request,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new UpdateCustomerProfileCommand(
                userId, request.firstName(), request.lastName(),
                request.phoneNumber(), request.profileImageUrl(), request.dateOfBirth());
        return updateCustomerProfileUseCase.execute(command)
                .map(result -> ResponseEntity.ok(toResponse(result)));
    }

    private CustomerProfileResponse toResponse(com.dsports.identity.application.result.CustomerProfileResult result) {
        return new CustomerProfileResponse(
                result.userId(), result.email(), result.firstName(), result.lastName(),
                result.phoneNumber(), result.profileImageUrl(), result.dateOfBirth(), result.roles());
    }
}
