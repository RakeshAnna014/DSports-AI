package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.CreateAddressCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.AddressLine;
import com.dsports.identity.domain.model.Country;
import com.dsports.identity.domain.model.PostalCode;
import com.dsports.identity.domain.model.State;
import reactor.core.publisher.Mono;

public class CreateAddressUseCase {

    private final UserRepository userRepository;

    public CreateAddressUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<AddressResult> execute(CreateAddressCommand command) {
        return Mono.defer(() -> {
            var line1 = AddressLine.from(command.line1());
            var line2 = command.line2() != null && !command.line2().isBlank()
                    ? AddressLine.from(command.line2()) : null;
            var state = State.from(command.state());
            var country = Country.from(command.country());
            var postalCode = PostalCode.from(command.postalCode());

            if (command.city() == null || command.city().isBlank()) {
                return Mono.error(new IdentityDomainException(ErrorCode.INVALID_ADDRESS,
                        "City must not be blank"));
            }

            return userRepository.findById(command.userId())
                    .switchIfEmpty(Mono.error(
                            new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")))
                    .flatMap(user -> {
                        var address = user.addAddress(line1, line2, command.city().strip(),
                                state, country, postalCode, command.type());
                        return userRepository.save(user)
                                .thenReturn(new AddressResult(
                                        address.getId().value(), address.getType(),
                                        address.getLine1().value(),
                                        address.getLine2() != null ? address.getLine2().value() : null,
                                        address.getCity(), address.getState().value(),
                                        address.getCountry().value(), address.getPostalCode().value(),
                                        address.isDefault(), address.getCreatedAt(), address.getUpdatedAt()));
                    });
        });
    }
}
