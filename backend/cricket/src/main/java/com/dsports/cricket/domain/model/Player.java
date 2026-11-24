package com.dsports.cricket.domain.model;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.dsports.cricket.domain.model.Rules.optionalText;
import static com.dsports.cricket.domain.model.Rules.require;
import static com.dsports.cricket.domain.model.Rules.text;

/**
 * A person who plays. Many local players never sign up, so a player exists without an account (a <b>guest</b>, created by someone who vouches for
 * them). A guest becomes linked to a registered customer only when the guest's creator or an administrator approves that customer's claim; the phone
 * number is only a hint for finding the profile, never proof. One customer has at most one linked player.
 */
public final class Player {

    /** Everything that can be edited about a player. {@code phone} is raw input; it is normalised on the way in. */
    public record Details(String name, String phone, PlayingRole role, BattingHand battingHand, String bowlingStyle, String photoUrl) {
    }

    private final UUID id;
    private Details details;
    private UUID customerId;
    private boolean publicProfile;
    private final UUID createdBy;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Player(UUID id, Details details, UUID customerId, boolean publicProfile, UUID createdBy, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.details = details;
        this.customerId = customerId;
        this.publicProfile = publicProfile;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A guest: no account, created by {@code createdBy}. */
    public static Player guest(UUID createdBy, Details details, Instant now) {
        return new Player(UUID.randomUUID(), normalise(details), null, true, createdBy, 0, now, now);
    }

    /** A registered customer's own profile: linked to them from the start. */
    public static Player forCustomer(UUID customerId, Details details, Instant now) {
        return new Player(UUID.randomUUID(), normalise(details), customerId, true, customerId, 0, now, now);
    }

    public static Player reconstitute(UUID id, Details details, UUID customerId, UUID createdBy, int version, Instant createdAt, Instant updatedAt) {
        return reconstitute(id, details, customerId, true, createdBy, version, createdAt, updatedAt);
    }

    public static Player reconstitute(UUID id, Details details, UUID customerId, boolean publicProfile, UUID createdBy, int version, Instant createdAt, Instant updatedAt) {
        return new Player(id, details, customerId, publicProfile, createdBy, version, createdAt, updatedAt);
    }

    public void update(Details newDetails, Instant now) {
        this.details = normalise(newDetails);
        this.updatedAt = now;
    }

    /** Links this guest to a customer (after an approved claim). */
    public void linkTo(UUID customer, Instant now) {
        if (customerId != null) {
            throw new CricketDomainException(CricketErrorCode.PLAYER_ALREADY_LINKED, "This player is already linked to an account");
        }
        this.customerId = Objects.requireNonNull(customer);
        this.updatedAt = now;
    }

    public boolean isLinked() {
        return customerId != null;
    }

    /** Whether the player has a public statistics page and a link from public scorecards: only a player linked to an account who has not hidden it. A guest never has one. */
    public boolean isPublic() {
        return customerId != null && publicProfile;
    }

    /** The player's own choice to show or hide the public page. A guest has no account to decide, so the flag cannot be changed before a claim is approved. */
    public void setPublicProfile(boolean visible, Instant now) {
        if (customerId == null) {
            throw new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "Only a player linked to an account has a public profile to show or hide");
        }
        this.publicProfile = visible;
        this.updatedAt = now;
    }

    public boolean isPublicProfile() { return publicProfile; }

    /** Whether {@code user} may put this player in a team or a match: the person who created it, or the customer it is linked to. */
    public boolean usableBy(UUID user) {
        return createdBy.equals(user) || user.equals(customerId);
    }

    /** Whether {@code user} may see this player's phone number: only whoever created the profile. */
    public boolean phoneVisibleTo(UUID user) {
        return createdBy.equals(user);
    }

    private static Details normalise(Details d) {
        require(d != null, "Player details are required");
        var name = text(d.name(), 80, "Name");
        var phone = PhoneNumber.normalise(d.phone());
        var style = optionalText(d.bowlingStyle(), 40, "Bowling style");
        var photo = optionalText(d.photoUrl(), 500, "Photo link");
        require(photo == null || photo.startsWith("https://") || photo.startsWith("http://"), "The photo must be a http(s) link");
        return new Details(name, phone, d.role(), d.battingHand(), style, photo);
    }

    public UUID getId() { return id; }
    public Details getDetails() { return details; }
    public String getName() { return details.name(); }
    public String getPhone() { return details.phone(); }
    public UUID getCustomerId() { return customerId; }
    public UUID getCreatedBy() { return createdBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
