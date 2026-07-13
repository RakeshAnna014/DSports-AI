package com.dsports.identity.infrastructure.persistence.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("customer_auth_providers")
public class CustomerAuthProviderEntity {

    @Column("customer_id")
    private UUID customerId;

    private String provider;

    public CustomerAuthProviderEntity() {
    }

    public CustomerAuthProviderEntity(UUID customerId, String provider) {
        this.customerId = customerId;
        this.provider = provider;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
