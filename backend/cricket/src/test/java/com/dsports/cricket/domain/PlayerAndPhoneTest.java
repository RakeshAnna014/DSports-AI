package com.dsports.cricket.domain;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.PhoneNumber;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayingRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerAndPhoneTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final UUID CAPTAIN = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static Player.Details details(String name, String phone) {
        return new Player.Details(name, phone, PlayingRole.BATTER, BattingHand.LEFT, " off spin ", null);
    }

    // ---- phone numbers ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "98765 43210|+919876543210", "9876543210|+919876543210", "+91 98765-43210|+919876543210", "(98765) 43210|+919876543210",
        "919876543210|+919876543210", "+44 7911 123456|+447911123456", "  +1 (415) 555-0100  |+14155550100"})
    void aPhoneNumberIsNormalisedSoTheSameNumberAlwaysComparesEqual(String raw, String expected) {
        assertThat(PhoneNumber.normalise(raw)).isEqualTo(expected);
    }

    @Test
    void aBlankPhoneIsNoPhone() {
        assertThat(PhoneNumber.normalise(null)).isNull();
        assertThat(PhoneNumber.normalise("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "98765432", "abcdefghij", "+12", "+1234567890123456", "00 91 98765 43210", "98765 4321x"})
    void anImplausiblePhoneIsAValidationError(String raw) {
        assertThatThrownBy(() -> PhoneNumber.normalise(raw)).isInstanceOfSatisfying(CricketDomainException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.VALIDATION_ERROR));
    }

    @Test
    void aNameIsMaskedToTheFirstNameAndTheInitialOfTheLastOne() {
        assertThat(PhoneNumber.maskedName("Arjun Kumar")).isEqualTo("Arjun K.");
        assertThat(PhoneNumber.maskedName("  Arjun   Kumar Singh ")).isEqualTo("Arjun S.");
        assertThat(PhoneNumber.maskedName("Dhoni")).isEqualTo("Dhoni");
    }

    // ---- players ---------------------------------------------------------------------------------------------

    @Test
    void aGuestIsCreatedWithNormalisedDetailsAndNoAccount() {
        var guest = Player.guest(CAPTAIN, details("  Arjun Kumar ", "98765 43210"), NOW);

        assertThat(guest.getName()).isEqualTo("Arjun Kumar");
        assertThat(guest.getPhone()).isEqualTo("+919876543210");
        assertThat(guest.getDetails().bowlingStyle()).isEqualTo("off spin");
        assertThat(guest.isLinked()).isFalse();
        assertThat(guest.getCreatedBy()).isEqualTo(CAPTAIN);
        assertThat(guest.getCustomerId()).isNull();
    }

    @Test
    void aCustomersOwnProfileIsLinkedFromTheStart() {
        var own = Player.forCustomer(OTHER, details("Me", null), NOW);

        assertThat(own.isLinked()).isTrue();
        assertThat(own.getCustomerId()).isEqualTo(OTHER);
        assertThat(own.getCreatedBy()).isEqualTo(OTHER);
    }

    @Test
    void invalidDetailsAreRejected() {
        assertInvalid(details(" ", null));
        assertInvalid(details("x".repeat(81), null));
        assertInvalid(details("Arjun", "12"));
        assertInvalid(new Player.Details("Arjun", null, null, null, "x".repeat(41), null));
        assertInvalid(new Player.Details("Arjun", null, null, null, null, "javascript:alert(1)"));
        assertInvalid(new Player.Details("Arjun", null, null, null, null, "https://x.example/" + "a".repeat(500)));
    }

    @Test
    void linkingAGuestMakesItBelongToTheAccountExactlyOnce() {
        var guest = Player.guest(CAPTAIN, details("Arjun", null), NOW);

        guest.linkTo(OTHER, NOW.plusSeconds(5));

        assertThat(guest.getCustomerId()).isEqualTo(OTHER);
        assertThat(guest.isLinked()).isTrue();
        assertThatThrownBy(() -> guest.linkTo(UUID.randomUUID(), NOW)).isInstanceOfSatisfying(CricketDomainException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.PLAYER_ALREADY_LINKED));
    }

    @Test
    void linkingAGuestKeepsTheSamePlayerIdSoEveryMatchAndCareerStatisticOfTheGuestFollowsTheAccount() {
        var guest = Player.guest(CAPTAIN, details("Arjun", null), NOW);
        var idBefore = guest.getId();

        guest.linkTo(OTHER, NOW.plusSeconds(5));

        assertThat(guest.getId()).as("career rows and match squads are keyed by this id").isEqualTo(idBefore);
        assertThat(guest.getName()).isEqualTo("Arjun");
    }

    @Test
    void aPlayerIsUsableByItsCreatorAndByTheAccountItIsLinkedToButNobodyElse() {
        var guest = Player.guest(CAPTAIN, details("Arjun", "9876543210"), NOW);
        guest.linkTo(OTHER, NOW);

        assertThat(guest.usableBy(CAPTAIN)).isTrue();
        assertThat(guest.usableBy(OTHER)).isTrue();
        assertThat(guest.usableBy(UUID.randomUUID())).isFalse();
        assertThat(guest.phoneVisibleTo(CAPTAIN)).isTrue();
        assertThat(guest.phoneVisibleTo(OTHER)).as("the phone belongs to whoever created the profile").isFalse();
    }

    @Test
    void updatingRevalidatesAndKeepsTheOwnership() {
        var guest = Player.guest(CAPTAIN, details("Arjun", null), NOW);

        guest.update(details("Arjun K", "9876543210"), NOW.plusSeconds(1));

        assertThat(guest.getName()).isEqualTo("Arjun K");
        assertThat(guest.getPhone()).isEqualTo("+919876543210");
        assertThat(guest.getCreatedBy()).isEqualTo(CAPTAIN);
        assertThatThrownBy(() -> guest.update(details("", null), NOW)).isInstanceOf(CricketDomainException.class);
    }

    private static void assertInvalid(Player.Details d) {
        assertThatThrownBy(() -> Player.guest(CAPTAIN, d, NOW)).isInstanceOfSatisfying(CricketDomainException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.VALIDATION_ERROR));
    }
}
