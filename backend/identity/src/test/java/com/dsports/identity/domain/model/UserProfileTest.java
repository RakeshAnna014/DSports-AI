package com.dsports.identity.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileTest {

    private User user;
    private UserProfileManagementService profileService;

    @BeforeEach
    void setUp() {
        user = User.reconstitute(
                UserId.generate(), Email.from("test@example.com"), "hash",
                CustomerName.of("John", "Doe"), null, null, null,
                UserStatus.ACTIVE, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                java.time.Instant.now(), java.time.Instant.now(), null
        );
        profileService = new UserProfileManagementService();
    }

    @Test
    void shouldUpdateAllProfileFields() {
        profileService.updateProfile(
                user,
                CustomerName.of("Jane", "Smith"),
                PhoneNumber.from("+919876543210"),
                "https://example.com/avatar.jpg",
                DateOfBirth.from(LocalDate.of(1990, 5, 15))
        );

        assertThat(user.getCustomerName().firstName()).isEqualTo("Jane");
        assertThat(user.getCustomerName().lastName()).isEqualTo("Smith");
        assertThat(user.getPhone()).isPresent();
        assertThat(user.getPhone().get().value()).isEqualTo("+919876543210");
        assertThat(user.getProfileImageUrl()).isPresent();
        assertThat(user.getProfileImageUrl().get()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(user.getDateOfBirth()).isPresent();
        assertThat(user.getDateOfBirth().get().value()).isEqualTo(LocalDate.of(1990, 5, 15));
    }

    @Test
    void shouldClearPhoneWhenNull() {
        var phoneUser = User.reconstitute(
                user.getId(), Email.from("test@example.com"), "hash",
                CustomerName.of("John", "Doe"), PhoneNumber.from("+919876543210"), null, null,
                UserStatus.ACTIVE, Set.of(UserRole.CUSTOMER), Set.of(AuthenticationProvider.EMAIL),
                0, null, null,
                java.time.Instant.now(), java.time.Instant.now(), null
        );

        profileService.updateProfile(
                phoneUser,
                CustomerName.of("John", "Doe"),
                null, null, null
        );

        assertThat(phoneUser.getPhone()).isEmpty();
    }

    @Test
    void shouldNotChangeEmail() {
        var originalEmail = user.getEmail();

        profileService.updateProfile(
                user,
                CustomerName.of("Jane", "Smith"),
                null, null, null
        );

        assertThat(user.getEmail()).isEqualTo(originalEmail);
    }

    @Test
    void shouldNotChangeUserId() {
        var originalId = user.getId();

        profileService.updateProfile(
                user,
                CustomerName.of("Jane", "Smith"),
                null, null, null
        );

        assertThat(user.getId()).isEqualTo(originalId);
    }

    @Test
    void shouldUpdateTimestampOnProfileChange() {
        var before = user.getUpdatedAt();

        profileService.updateProfile(
                user,
                CustomerName.of("Jane", "Smith"),
                null, null, null
        );

        assertThat(user.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
