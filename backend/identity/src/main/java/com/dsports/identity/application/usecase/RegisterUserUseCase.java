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
import reactor.core.publisher.Mono;

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

    public Mono<RegisterUserResult> execute(RegisterUserCommand command) {
        Email email = Email.from(command.email());
        CustomerName customerName = CustomerName.of(command.firstName(), command.lastName());

        return userRepository.findByEmail(email)
                .<RegisterUserResult>flatMap(ignored ->
                    Mono.error(new IdentityDomainException(ErrorCode.DUPLICATE_EMAIL,
                            "Email " + command.email() + " is already registered")))
                .switchIfEmpty(Mono.defer(() -> {
                    String encodedPassword = passwordEncoder.encode(command.password());
                    User user = User.register(email, customerName, encodedPassword);
                    return userRepository.save(user)
                            .then(Mono.fromRunnable(() -> {
                                eventPublisher.publishAll(user.getDomainEvents());
                                user.clearDomainEvents();
                            }))
                            .thenReturn(new RegisterUserResult(user.getId().value(), user.getEmail().value()));
                }));
    }
}
