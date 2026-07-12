package com.dsports.identity.infrastructure.persistence.adapter;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.infrastructure.persistence.entity.UserEntity;
import com.dsports.identity.infrastructure.persistence.mapper.UserPersistenceMapper;
import com.dsports.identity.infrastructure.persistence.repository.UserR2dbcRepository;

import java.util.Optional;

public class UserRepositoryAdapter implements UserRepository {

    private final UserR2dbcRepository r2dbcRepository;
    private final UserPersistenceMapper mapper;

    public UserRepositoryAdapter(UserR2dbcRepository r2dbcRepository, UserPersistenceMapper mapper) {
        this.r2dbcRepository = r2dbcRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return r2dbcRepository.findByEmail(email.value())
                .map(mapper::toDomain)
                .blockOptional();
    }

    @Override
    public Optional<User> findById(UserId id) {
        return r2dbcRepository.findById(id.value())
                .map(mapper::toDomain)
                .blockOptional();
    }

    @Override
    public void save(User user) {
        UserEntity entity = mapper.toEntity(user);
        r2dbcRepository.save(entity).block();
    }
}
