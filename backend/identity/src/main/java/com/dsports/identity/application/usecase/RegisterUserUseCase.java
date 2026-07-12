package com.dsports.identity.application.usecase;

import com.dsports.identity.application.command.RegisterUserCommand;
import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.PasswordEncoder;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.application.result.RegisterUserResult;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;

public class RegisterUserUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher eventPublisher;

    public RegisterUserUseCase(UserRepository userRepository, PasswordEncoder passwordEncoder,
                               EventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    public RegisterUserResult execute(RegisterUserCommand command) {
        Email email = Email.from(command.email());
        CustomerName customerName = CustomerName.of(command.firstName(), command.lastName());

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IdentityDomainException(ErrorCode.DUPLICATE_EMAIL,
                    "Email " + command.email() + " is already registered");
        }

        String encodedPassword = passwordEncoder.encode(command.password());
        User user = User.register(email, customerName, encodedPassword);

        userRepository.save(user);
        eventPublisher.publishAll(user.getDomainEvents());
        user.clearDomainEvents();

        return new RegisterUserResult(user.getId().value(), user.getEmail().value());
    }
}
