package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AddressListResult;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.UserId;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

public class GetAddressesUseCase {

    private final UserRepository userRepository;

    public GetAddressesUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<AddressListResult> execute(UserId userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(
                        new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")))
                .map(user -> {
                    var list = user.getAddresses().stream()
                            .map(a -> new AddressResult(
                                    a.getId().value(), a.getType().name(),
                                    a.getLine1().value(), a.getLine2() != null ? a.getLine2().value() : null,
                                    a.getCity(), a.getState().value(), a.getCountry().value(),
                                    a.getPostalCode().value(), a.isDefault(),
                                    a.getCreatedAt(), a.getUpdatedAt()))
                            .collect(Collectors.toList());
                    return AddressListResult.from(list);
                });
    }
}
