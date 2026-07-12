package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.AuthenticationFailureReason;
import com.dsports.identity.application.result.AuthenticationResult;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;

public class AuthenticationUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthenticationResult execute(LoginUserCommand command) {
        Email email = Email.from(command.email());

        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return AuthenticationResult.failure(AuthenticationFailureReason.USER_NOT_FOUND);
        }

        User user = userOpt.get();

        if (user.getStatus().isDeleted()) {
            return AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_DELETED);
        }

        if (user.getStatus().isDisabled()) {
            return AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_DISABLED);
        }

        if (!user.canLogin()) {
            return AuthenticationResult.failure(AuthenticationFailureReason.ACCOUNT_LOCKED);
        }

        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || !passwordEncoder.matches(command.password(), passwordHash)) {
            user.recordFailedLogin();
            userRepository.save(user);
            return AuthenticationResult.failure(AuthenticationFailureReason.INVALID_PASSWORD);
        }

        user.updateLastLogin();
        userRepository.save(user);

        return AuthenticationResult.success(user.getId().value(), user.getEmail().value());
    }
}
