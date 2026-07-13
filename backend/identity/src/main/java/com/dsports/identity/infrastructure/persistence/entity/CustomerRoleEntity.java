package com.dsports.identity.infrastructure.persistence.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("customer_roles")
public class CustomerRoleEntity {

    @Column("customer_id")
    private UUID customerId;

    private String role;

    public CustomerRoleEntity() {
    }

    public CustomerRoleEntity(UUID customerId, String role) {
        this.customerId = customerId;
        this.role = role;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
