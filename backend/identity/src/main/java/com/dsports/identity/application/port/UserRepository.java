package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(Email email);
    Optional<User> findById(UserId id);
    boolean existsByEmail(Email email);
    void save(User user);
}
