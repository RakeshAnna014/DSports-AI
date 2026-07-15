package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.SetDefaultAddressCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import reactor.core.publisher.Mono;

public class SetDefaultAddressUseCase {

    private final UserRepository userRepository;

    public SetDefaultAddressUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<AddressResult> execute(SetDefaultAddressCommand command) {
        return userRepository.findById(command.userId())
                .switchIfEmpty(Mono.error(
                        new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    user.setDefaultAddress(command.addressId());
                    return userRepository.save(user)
                            .then(Mono.fromCallable(() -> {
                                var addr = user.getAddressById(command.addressId())
                                        .orElseThrow();
                                return new AddressResult(
                                        addr.getId().value(), addr.getType(),
                                        addr.getLine1().value(),
                                        addr.getLine2() != null ? addr.getLine2().value() : null,
                                        addr.getCity(), addr.getState().value(),
                                        addr.getCountry().value(), addr.getPostalCode().value(),
                                        addr.isDefault(), addr.getCreatedAt(), addr.getUpdatedAt());
                            }));
                });
    }
}
