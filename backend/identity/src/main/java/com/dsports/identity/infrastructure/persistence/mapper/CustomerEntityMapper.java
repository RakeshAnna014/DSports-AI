package com.dsports.identity.infrastructure.persistence.mapper;

import com.dsports.identity.domain.model.Address;
import com.dsports.identity.domain.model.AddressId;
import com.dsports.identity.domain.model.AddressLine;
import com.dsports.identity.domain.model.AddressType;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.Country;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.DateOfBirth;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.PhoneNumber;
import com.dsports.identity.domain.model.PostalCode;
import com.dsports.identity.domain.model.ProfileImageUrl;
import com.dsports.identity.domain.model.State;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import com.dsports.identity.infrastructure.persistence.entity.AddressEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerAuthProviderEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerRoleEntity;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class CustomerEntityMapper {

    public CustomerEntity toEntity(User domain) {
        CustomerEntity entity = new CustomerEntity();
        entity.setId(domain.getId().value());
        entity.setEmail(domain.getEmail().value());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFirstName(domain.getCustomerName().firstName());
        entity.setLastName(domain.getCustomerName().lastName());
        domain.getPhone().ifPresent(phone -> entity.setPhone(phone.value()));
        domain.getProfileImageUrl().ifPresent(url -> entity.setProfileImageUrl(url.value()));
        domain.getDateOfBirth().ifPresent(dob -> entity.setDateOfBirth(dob.value()));
        entity.setStatus(domain.getStatus().name());
        entity.setFailedLoginAttempts(domain.getFailedLoginAttempts());
        domain.getLockedUntil().ifPresent(entity::setLockedUntil);
        domain.getLastLoginAt().ifPresent(entity::setLastLoginAt);
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        domain.getDeletedAt().ifPresent(entity::setDeletedAt);
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public User toDomain(CustomerEntity entity,
                          Set<CustomerRoleEntity> roleEntities,
                          Set<CustomerAuthProviderEntity> providerEntities,
                          List<AddressEntity> addressEntities) {
        return User.reconstitute(
                UserId.fromUUID(entity.getId()),
                Email.from(entity.getEmail()),
                entity.getPasswordHash(),
                CustomerName.of(entity.getFirstName(), entity.getLastName()),
                entity.getPhone() != null ? PhoneNumber.from(entity.getPhone()) : null,
                entity.getProfileImageUrl() != null ? ProfileImageUrl.from(entity.getProfileImageUrl()) : null,
                entity.getDateOfBirth() != null ? DateOfBirth.from(entity.getDateOfBirth()) : null,
                UserStatus.valueOf(entity.getStatus()),
                toRoles(roleEntities),
                toAuthProviders(providerEntities),
                entity.getFailedLoginAttempts(),
                entity.getLockedUntil(),
                entity.getLastLoginAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt(),
                toAddresses(addressEntities),
                entity.getVersion()
        );
    }

    private List<Address> toAddresses(List<AddressEntity> addressEntities) {
        if (addressEntities == null || addressEntities.isEmpty()) {
            return Collections.emptyList();
        }
        return addressEntities.stream()
                .map(e -> Address.reconstitute(
                        AddressId.fromUUID(e.getId()),
                        AddressType.valueOf(e.getType()),
                        AddressLine.from(e.getLine1()),
                        e.getLine2() != null ? AddressLine.from(e.getLine2()) : null,
                        e.getCity(),
                        State.from(e.getState()),
                        Country.from(e.getCountry()),
                        PostalCode.from(e.getPostalCode()),
                        e.isDefault(),
                        e.getCreatedAt(),
                        e.getUpdatedAt()
                ))
                .collect(Collectors.toList());
    }

    public Set<CustomerRoleEntity> toRoleEntities(UUID customerId, Set<UserRole> roles) {
        return roles.stream()
                .map(role -> new CustomerRoleEntity(customerId, role.name()))
                .collect(Collectors.toSet());
    }

    public Set<CustomerAuthProviderEntity> toAuthProviderEntities(UUID customerId, Set<AuthenticationProvider> providers) {
        return providers.stream()
                .map(provider -> new CustomerAuthProviderEntity(customerId, provider.name()))
                .collect(Collectors.toSet());
    }

    private Set<UserRole> toRoles(Set<CustomerRoleEntity> roleEntities) {
        if (roleEntities == null || roleEntities.isEmpty()) {
            return Collections.emptySet();
        }
        return roleEntities.stream()
                .map(e -> UserRole.valueOf(e.getRole()))
                .collect(Collectors.toSet());
    }

    private Set<AuthenticationProvider> toAuthProviders(Set<CustomerAuthProviderEntity> providerEntities) {
        if (providerEntities == null || providerEntities.isEmpty()) {
            return Collections.emptySet();
        }
        return providerEntities.stream()
                .map(e -> AuthenticationProvider.valueOf(e.getProvider()))
                .collect(Collectors.toSet());
    }
}
