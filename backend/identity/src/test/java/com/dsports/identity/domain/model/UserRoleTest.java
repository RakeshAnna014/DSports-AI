package com.dsports.identity.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRoleTest {

    @Test
    void buyerRolesOfProductIntelligenceCanBeStoredAndMappedWithoutBreakingLogin() {
        // customer_roles.role is mapped with UserRole.valueOf: an unknown name would make that user's login fail
        assertEquals(UserRole.PROCUREMENT_BUYER, UserRole.valueOf("PROCUREMENT_BUYER"));
        assertEquals(UserRole.CATEGORY_MANAGER, UserRole.valueOf("CATEGORY_MANAGER"));
        assertEquals(UserRole.SALES_PLANNER, UserRole.valueOf("SALES_PLANNER"));
        assertEquals(UserRole.REGIONAL_MANAGER, UserRole.valueOf("REGIONAL_MANAGER"));
        assertEquals(UserRole.STORE_MANAGER, UserRole.valueOf("STORE_MANAGER"));
    }

    @Test
    void buyerRolesNeverOutrankSupportOrAdministrators() {
        assertFalse(UserRole.PROCUREMENT_BUYER.isAtLeast(UserRole.SUPPORT_EXECUTIVE));
        assertFalse(UserRole.CATEGORY_MANAGER.isAtLeast(UserRole.ADMIN));
        assertTrue(UserRole.ADMIN.isStrictlyHigherThan(UserRole.CATEGORY_MANAGER));
    }
}
