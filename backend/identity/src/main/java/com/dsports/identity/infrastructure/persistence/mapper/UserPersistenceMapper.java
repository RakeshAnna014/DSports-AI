package com.dsports.identity.infrastructure.persistence.mapper;

import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.PhoneNumber;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import com.dsports.identity.infrastructure.persistence.entity.UserEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public class UserPersistenceMapper {

    public UserEntity toEntity(User domain) {
        UserEntity entity = new UserEntity();
        entity.setId(domain.getId().value());
        entity.setEmail(domain.getEmail().value());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFirstName(domain.getCustomerName().firstName());
        entity.setLastName(domain.getCustomerName().lastName());
        domain.getPhone().ifPresent(phone -> entity.setPhone(phone.value()));
        entity.setStatus(domain.getStatus().name());
        entity.setRoles(domain.getRoles().stream()
                .map(Enum::name)
                .collect(Collectors.joining(",")));
        entity.setAuthProviders(domain.getAuthProviders().stream()
                .map(Enum::name)
                .collect(Collectors.joining(",")));
        entity.setFailedLoginAttempts(domain.getFailedLoginAttempts());
        domain.getLockedUntil().ifPresent(entity::setLockedUntil);
        domain.getLastLoginAt().ifPresent(entity::setLastLoginAt);
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        domain.getDeletedAt().ifPresent(entity::setDeletedAt);
        return entity;
    }

    public User toDomain(UserEntity entity) {
        return User.reconstitute(
                UserId.fromUUID(entity.getId()),
                Email.from(entity.getEmail()),
                entity.getPasswordHash(),
                CustomerName.of(entity.getFirstName(), entity.getLastName()),
                entity.getPhone() != null ? PhoneNumber.from(entity.getPhone()) : null,
                UserStatus.valueOf(entity.getStatus()),
                parseRoles(entity.getRoles()),
                parseAuthProviders(entity.getAuthProviders()),
                entity.getFailedLoginAttempts(),
                entity.getLockedUntil(),
                entity.getLastLoginAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    private Set<UserRole> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(roles.split(","))
                .map(UserRole::valueOf)
                .collect(Collectors.toSet());
    }

    private Set<AuthenticationProvider> parseAuthProviders(String authProviders) {
        if (authProviders == null || authProviders.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(authProviders.split(","))
                .map(AuthenticationProvider::valueOf)
                .collect(Collectors.toSet());
    }
}
