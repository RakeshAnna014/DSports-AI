package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.UpdateCustomerProfileCommand;
import com.dsports.identity.application.usecase.GetCustomerProfileUseCase;
import com.dsports.identity.application.usecase.UpdateCustomerProfileUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.CustomerProfileResponse;
import com.dsports.identity.interfaces.dto.UpdateCustomerProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Customer Profile")
@SecurityRequirement(name = "bearer-jwt")
public class CustomerController {

    private final GetCustomerProfileUseCase getCustomerProfileUseCase;
    private final UpdateCustomerProfileUseCase updateCustomerProfileUseCase;

    public CustomerController(GetCustomerProfileUseCase getCustomerProfileUseCase,
                              UpdateCustomerProfileUseCase updateCustomerProfileUseCase) {
        this.getCustomerProfileUseCase = getCustomerProfileUseCase;
        this.updateCustomerProfileUseCase = updateCustomerProfileUseCase;
    }

    @GetMapping("/me")
    @Operation(summary = "Get customer profile", description = "Retrieve the authenticated customer's profile information")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile retrieved successfully",
            content = @Content(schema = @Schema(implementation = CustomerProfileResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<CustomerProfileResponse>> getProfile(Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        return getCustomerProfileUseCase.execute(userId)
                .map(result -> ResponseEntity.ok(toResponse(result)));
    }

    @PutMapping("/me")
    @Operation(summary = "Update customer profile", description = "Update the authenticated customer's profile information")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile updated successfully",
            content = @Content(schema = @Schema(implementation = CustomerProfileResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
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
