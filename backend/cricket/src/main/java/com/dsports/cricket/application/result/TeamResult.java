package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.PlayingRole;

import java.util.List;
import java.util.UUID;

/** A team with its members. {@code canEdit}: the viewer is the creator or the captain's own account. */
public record TeamResult(UUID id, String name, String city, String status, int version, boolean canEdit, List<Member> members) {

    public record Member(UUID playerId, String name, PlayingRole role, BattingHand battingHand, boolean captain, boolean linked, String phone) {
    }
}
