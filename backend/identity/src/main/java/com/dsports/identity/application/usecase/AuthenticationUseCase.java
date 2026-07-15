package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.application.result.AuthenticationResult;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import reactor.core.publisher.Mono;

public class AuthenticationUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Mono<AuthenticationResult> execute(LoginUserCommand command) {
        Email email = Email.from(command.email());

        return userRepository.findByEmail(email)
                .flatMap(user -> {
                    if (user.getStatus().isDeleted()) {
                        return Mono.just(AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_DELETED));
                    }

                    if (user.getStatus().isDisabled()) {
                        return Mono.just(AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_DISABLED));
                    }

                    if (!user.canLogin()) {
                        return Mono.just(AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED));
                    }

                    String passwordHash = user.getPasswordHash();
                    if (passwordHash == null || !passwordEncoder.matches(command.password(), passwordHash)) {
                        user.recordFailedLogin();
                        return userRepository.save(user)
                                .thenReturn(AuthenticationResult.failure(AuthenticationFailureReason.INVALID_PASSWORD));
                    }

                    user.updateLastLogin();
                    return userRepository.save(user)
                            .thenReturn(AuthenticationResult.success(user.getId().value(), user.getEmail().value()));
                })
                .switchIfEmpty(Mono.just(AuthenticationResult.failure(AuthenticationFailureReason.USER_NOT_FOUND)));
    }
}
