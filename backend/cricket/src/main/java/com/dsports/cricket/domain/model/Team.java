package com.dsports.cricket.domain.model;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dsports.cricket.domain.model.Rules.optionalText;
import static com.dsports.cricket.domain.model.Rules.text;

/**
 * A team: a name, a city and members (players), at most one of them the captain. An archived team keeps its history but can no longer change.
 * The member list is part of the aggregate, so "at most {@value #MAX_MEMBERS} members" and "one captain" are enforced in one place (and again by the
 * database: a unique partial index on the captain).
 */
public final class Team {

    public static final int MAX_MEMBERS = 30;

    public record Member(UUID playerId, boolean captain) {
    }

    private final UUID id;
    private String name;
    private String city;
    private TeamStatus status;
    private final UUID createdBy;
    private final List<Member> members;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Team(UUID id, String name, String city, TeamStatus status, UUID createdBy, List<Member> members, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = name;
        this.city = city;
        this.status = status;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.members = new ArrayList<>(members);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Team create(UUID createdBy, String name, String city, Instant now) {
        return new Team(UUID.randomUUID(), text(name, 60, "Team name"), optionalText(city, 80, "City"), TeamStatus.ACTIVE, createdBy, List.of(), 0, now, now);
    }

    public static Team reconstitute(UUID id, String name, String city, TeamStatus status, UUID createdBy, List<Member> members, int version, Instant createdAt, Instant updatedAt) {
        return new Team(id, name, city, status, createdBy, members, version, createdAt, updatedAt);
    }

    public void update(String newName, String newCity, Instant now) {
        requireActive();
        this.name = text(newName, 60, "Team name");
        this.city = optionalText(newCity, 80, "City");
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        requireActive();
        this.status = TeamStatus.ARCHIVED;
        this.updatedAt = now;
    }

    public void addMember(UUID playerId, Instant now) {
        requireActive();
        if (isMember(playerId)) {
            throw new CricketDomainException(CricketErrorCode.ALREADY_A_MEMBER, "That player is already in the team");
        }
        if (members.size() >= MAX_MEMBERS) {
            throw new CricketDomainException(CricketErrorCode.TEAM_FULL, "A team has at most " + MAX_MEMBERS + " players");
        }
        members.add(new Member(playerId, false));
        this.updatedAt = now;
    }

    /** The captain cannot be removed: name another captain first. */
    public void removeMember(UUID playerId, Instant now) {
        requireActive();
        var member = members.stream().filter(m -> m.playerId().equals(playerId)).findFirst()
            .orElseThrow(() -> new CricketDomainException(CricketErrorCode.NOT_A_MEMBER, "That player is not in the team"));
        if (member.captain()) {
            throw new CricketDomainException(CricketErrorCode.CANNOT_REMOVE_CAPTAIN, "Choose another captain before removing the captain");
        }
        members.remove(member);
        this.updatedAt = now;
    }

    public void setCaptain(UUID playerId, Instant now) {
        requireActive();
        if (!isMember(playerId)) {
            throw new CricketDomainException(CricketErrorCode.NOT_A_MEMBER, "The captain must be a member of the team");
        }
        members.replaceAll(m -> new Member(m.playerId(), m.playerId().equals(playerId)));
        this.updatedAt = now;
    }

    public boolean isMember(UUID playerId) {
        return members.stream().anyMatch(m -> m.playerId().equals(playerId));
    }

    public Optional<UUID> captainId() {
        return members.stream().filter(Member::captain).map(Member::playerId).findFirst();
    }

    private void requireActive() {
        if (status == TeamStatus.ARCHIVED) {
            throw new CricketDomainException(CricketErrorCode.TEAM_ARCHIVED, "This team is archived and can no longer be changed");
        }
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public TeamStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public List<Member> getMembers() { return List.copyOf(members); }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
