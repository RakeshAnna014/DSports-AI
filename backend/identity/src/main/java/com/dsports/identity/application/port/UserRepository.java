package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import reactor.core.publisher.Mono;

public interface UserRepository {
    Mono<User> findByEmail(Email email);
    Mono<User> findById(UserId id);
    Mono<Boolean> existsByEmail(Email email);
    Mono<Void> save(User user);
}
