package com.dsports.identity.domain.model;

import java.util.Objects;

public final class UserProfileManagementService {

    public UserProfileManagementService() {
    }

    public void changePhone(User user, PhoneNumber newPhone) {
        Objects.requireNonNull(user, "user must not be null");
        user.updatePhone(newPhone);
    }

    public void changePasswordHash(User user, String newPasswordHash) {
        Objects.requireNonNull(user, "user must not be null");
        user.updatePasswordHash(newPasswordHash);
    }

    public void updateProfile(User user, CustomerName newName, PhoneNumber newPhone,
                              String newProfileImageUrl, DateOfBirth newDateOfBirth) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        user.updateProfile(newName, newPhone, newProfileImageUrl, newDateOfBirth);
    }
}
