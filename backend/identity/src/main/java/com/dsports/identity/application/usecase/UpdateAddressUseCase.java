package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.UpdateAddressCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.AddressLine;
import com.dsports.identity.domain.model.Country;
import com.dsports.identity.domain.model.PostalCode;
import com.dsports.identity.domain.model.State;
import reactor.core.publisher.Mono;

public class UpdateAddressUseCase {

    private final UserRepository userRepository;

    public UpdateAddressUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<AddressResult> execute(UpdateAddressCommand command) {
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
                        user.updateAddress(command.addressId(), line1, line2, command.city().strip(),
                                state, country, postalCode, command.type());
                        return userRepository.save(user)
                                .then(Mono.fromCallable(() -> {
                                    var addr = user.getAddressById(command.addressId())
                                            .orElseThrow();
                                    return new AddressResult(
                                            addr.getId().value(), addr.getType().name(),
                                            addr.getLine1().value(),
                                            addr.getLine2() != null ? addr.getLine2().value() : null,
                                            addr.getCity(), addr.getState().value(),
                                            addr.getCountry().value(), addr.getPostalCode().value(),
                                            addr.isDefault(), addr.getCreatedAt(), addr.getUpdatedAt());
                                }));
                    });
        });
    }
}
