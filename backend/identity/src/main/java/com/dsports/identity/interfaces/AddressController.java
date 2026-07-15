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
    public Mono<ResponseEntity<AddressListResponse>> getAddresses(Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        return getAddressesUseCase.execute(userId)
                .map(result -> ResponseEntity.ok(AddressListResponse.from(result)));
    }

    @PostMapping
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
    public Mono<ResponseEntity<Void>> deleteAddress(
            @PathVariable UUID addressId,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new DeleteAddressCommand(userId, AddressId.fromUUID(addressId));
        return deleteAddressUseCase.execute(command)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PutMapping("/{addressId}/default")
    public Mono<ResponseEntity<AddressResponse>> setDefaultAddress(
            @PathVariable UUID addressId,
            Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var command = new SetDefaultAddressCommand(userId, AddressId.fromUUID(addressId));
        return setDefaultAddressUseCase.execute(command)
                .map(result -> ResponseEntity.ok(AddressResponse.from(result)));
    }
}
