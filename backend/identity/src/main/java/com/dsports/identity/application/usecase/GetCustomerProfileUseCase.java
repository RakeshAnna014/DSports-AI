package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.CustomerProfileResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.UserId;
import reactor.core.publisher.Mono;

public class GetCustomerProfileUseCase {

    private final UserRepository userRepository;

    public GetCustomerProfileUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<CustomerProfileResult> execute(UserId userId) {
        return userRepository.findById(userId)
                .map(user -> {
                    var roles = user.getRoles().stream()
                            .map(Enum::name)
                            .toList();
                    return new CustomerProfileResult(
                            user.getId().value(),
                            user.getEmail().value(),
                            user.getCustomerName().firstName(),
                            user.getCustomerName().lastName(),
                            user.getPhone().map(p -> p.value()).orElse(null),
                            user.getProfileImageUrl().map(p -> p.value()).orElse(null),
                            user.getDateOfBirth().map(d -> d.value()).orElse(null),
                            roles
                    );
                })
                .switchIfEmpty(Mono.error(
                        new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")));
    }
}
