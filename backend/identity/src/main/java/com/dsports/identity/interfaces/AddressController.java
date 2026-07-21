package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.CreateAddressCommand;
import com.dsports.identity.application.command.DeleteAddressCommand;
import com.dsports.identity.application.command.SetDefaultAddressCommand;
import com.dsports.identity.application.command.UpdateAddressCommand;
import com.dsports.identity.application.usecase.CreateAddressUseCase;
import com.dsports.identity.application.usecase.DeleteAddressUseCase;
import com.dsports.identity.application.usecase.GetAddressesUseCase;
import com.dsports.identity.application.usecase.SetDefaultAddressUseCase;
import com.dsports.identity.application.usecase.UpdateAddressUseCase;
import com.dsports.identity.domain.model.AddressId;
import com.dsports.identity.domain.model.AddressType;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.AddressListResponse;
import com.dsports.identity.interfaces.dto.AddressResponse;
import com.dsports.identity.interfaces.dto.CreateAddressRequest;
import com.dsports.identity.interfaces.dto.UpdateAddressRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers/me/addresses")
@Tag(name = "Addresses")
@SecurityRequirement(name = "bearer-jwt")
public class AddressController {

    private final GetAddressesUseCase getAddressesUseCase;
    private final CreateAddressUseCase createAddressUseCase;
    private final UpdateAddressUseCase updateAddressUseCase;
    private final DeleteAddressUseCase deleteAddressUseCase;
    private final SetDefaultAddressUseCase setDefaultAddressUseCase;

    public AddressController(GetAddressesUseCase getAddressesUseCase,
                              CreateAddressUseCase createAddressUseCase,
                              UpdateAddressUseCase updateAddressUseCase,
                              DeleteAddressUseCase deleteAddressUseCase,
                              SetDefaultAddressUseCase setDefaultAddressUseCase) {
        this.getAddressesUseCase = getAddressesUseCase;
        this.createAddressUseCase = createAddressUseCase;
        this.updateAddressUseCase = updateAddressUseCase;
        this.deleteAddressUseCase = deleteAddressUseCase;
        this.setDefaultAddressUseCase = setDefaultAddressUseCase;
    }

    @GetMapping
    @Operation(summary = "List addresses", description = "Retrieve all addresses for the authenticated customer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Addresses retrieved successfully",
            content = @Content(schema = @Schema(implementation = AddressListResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<AddressListResponse>> getAddresses(Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        return getAddressesUseCase.execute(userId)
                .map(result -> ResponseEntity.ok(AddressListResponse.from(result)));
    }

    @PostMapping
    @Operation(summary = "Create address", description = "Add a new address for the authenticated customer")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Address created successfully",
            content = @Content(schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<AddressResponse>> createAddress(
            @Valid @RequestBody CreateAddressRequest request,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new CreateAddressCommand(
                userId, request.line1(), request.line2(), request.city(),
                request.state(), request.country(), request.postalCode(),
                AddressType.valueOf(request.type()));
        return createAddressUseCase.execute(command)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(AddressResponse.from(result)));
    }

    @PutMapping("/{addressId}")
    @Operation(summary = "Update address", description = "Update an existing address for the authenticated customer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Address updated successfully",
            content = @Content(schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Address not found")
    })
    public Mono<ResponseEntity<AddressResponse>> updateAddress(
            @PathVariable UUID addressId,
            @Valid @RequestBody UpdateAddressRequest request,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new UpdateAddressCommand(
                userId, AddressId.fromUUID(addressId), request.line1(), request.line2(),
                request.city(), request.state(), request.country(), request.postalCode(),
                AddressType.valueOf(request.type()));
        return updateAddressUseCase.execute(command)
                .map(result -> ResponseEntity.ok(AddressResponse.from(result)));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Delete address", description = "Delete an address for the authenticated customer")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Address deleted successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Address not found")
    })
    public Mono<ResponseEntity<Void>> deleteAddress(
            @PathVariable UUID addressId,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new DeleteAddressCommand(userId, AddressId.fromUUID(addressId));
        return deleteAddressUseCase.execute(command)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PutMapping("/{addressId}/default")
    @Operation(summary = "Set default address", description = "Set an address as the default shipping address")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Default address updated",
            content = @Content(schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Address not found")
    })
    public Mono<ResponseEntity<AddressResponse>> setDefaultAddress(
            @PathVariable UUID addressId,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new SetDefaultAddressCommand(userId, AddressId.fromUUID(addressId));
        return setDefaultAddressUseCase.execute(command)
                .map(result -> ResponseEntity.ok(AddressResponse.from(result)));
    }
}
