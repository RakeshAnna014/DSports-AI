package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.DeleteAddressCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import reactor.core.publisher.Mono;

public class DeleteAddressUseCase {

    private final UserRepository userRepository;

    public DeleteAddressUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<Void> execute(DeleteAddressCommand command) {
        return userRepository.findById(command.userId())
                .switchIfEmpty(Mono.error(
                        new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    user.removeAddress(command.addressId());
                    return userRepository.save(user);
                });
    }
}
