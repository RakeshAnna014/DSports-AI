package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.UpdateCustomerProfileCommand;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.CustomerProfileResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.DateOfBirth;
import com.dsports.identity.domain.model.PhoneNumber;
import com.dsports.identity.domain.model.UserProfileManagementService;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public class UpdateCustomerProfileUseCase {

    private final UserRepository userRepository;
    private final UserProfileManagementService profileService;

    public UpdateCustomerProfileUseCase(UserRepository userRepository,
                                        UserProfileManagementService profileService) {
        this.userRepository = userRepository;
        this.profileService = profileService;
    }

    public Mono<CustomerProfileResult> execute(UpdateCustomerProfileCommand command) {
        return Mono.defer(() -> {
            var newName = CustomerName.of(command.firstName(), command.lastName());
            var newPhone = command.phoneNumber() != null && !command.phoneNumber().isBlank()
                    ? PhoneNumber.from(command.phoneNumber())
                    : null;
            var newDob = command.dateOfBirth() != null
                    ? DateOfBirth.from(command.dateOfBirth())
                    : null;
            var newProfileImageUrl = command.profileImageUrl();

            return userRepository.findById(command.userId())
                .switchIfEmpty(Mono.error(
                        new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    profileService.updateProfile(user, newName, newPhone, newProfileImageUrl, newDob);
                    return userRepository.save(user)
                            .then(Mono.fromCallable(() -> {
                                var roles = user.getRoles().stream()
                                        .map(Enum::name)
                                        .toList();
                                return new CustomerProfileResult(
                                        user.getId().value(),
                                        user.getEmail().value(),
                                        user.getCustomerName().firstName(),
                                        user.getCustomerName().lastName(),
                                        user.getPhone().map(p -> p.value()).orElse(null),
                                        user.getProfileImageUrl().orElse(null),
                                        user.getDateOfBirth().map(d -> d.value()).orElse(null),
                                        roles
                                );
                            }));
                });
        });
    }
}
