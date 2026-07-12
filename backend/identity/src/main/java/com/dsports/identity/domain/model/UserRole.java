package com.dsports.identity.domain.model;

// ADR-TODO-SPRINT-2: Consider evolving from hierarchy-based authorization to
// Role → Permission mapping. Current hierarchy is acceptable for Sprint 1:
// - Simple to implement and reason about for the initial set of roles.
// - The 0–7 level range covers the known role hierarchy (CUSTOMER → SUPER_ADMIN).
//
// Future direction:
//   Role (CUSTOMER, ADMIN, ...) ──→ Permission (READ_PRODUCT, WRITE_ORDER, ...)
//   Authorization: user.hasPermission(Permission.READ_PRODUCT) vs user.getRole().isAtLeast(...)
//
// This would allow:
//   - Granular access control per resource/action
//   - Role composition without hierarchy conflicts
//   - Dynamic permission assignment per user

public enum UserRole {

    CUSTOMER(0),
    CUSTOMER_B2B(1),
    FRANCHISE_OWNER(2),
    WAREHOUSE_MANAGER(3),
    INVENTORY_MANAGER(4),
    SUPPORT_EXECUTIVE(5),
    ADMIN(6),
    SUPER_ADMIN(7);

    private final int hierarchyLevel;

    UserRole(int hierarchyLevel) {
        this.hierarchyLevel = hierarchyLevel;
    }

    public boolean isAtLeast(UserRole other) {
        return this.hierarchyLevel >= other.hierarchyLevel;
    }

    public boolean isStrictlyHigherThan(UserRole other) {
        return this.hierarchyLevel > other.hierarchyLevel;
    }

    public int hierarchyLevel() {
        return hierarchyLevel;
    }
}
