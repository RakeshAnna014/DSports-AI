package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayingRole;

import java.util.UUID;

/** A player as the caller sees it. The phone number is shown only to whoever created the profile. {@code linked}: tied to a registered account. */
public record PlayerResult(UUID id, String name, String phone, PlayingRole role, BattingHand battingHand, String bowlingStyle, String photoUrl,
                           boolean linked, boolean mine, int version) {

    public static PlayerResult from(Player p, UUID viewer) {
        var d = p.getDetails();
        return new PlayerResult(p.getId(), d.name(), p.phoneVisibleTo(viewer) ? d.phone() : null, d.role(), d.battingHand(), d.bowlingStyle(), d.photoUrl(),
            p.isLinked(), viewer.equals(p.getCustomerId()), p.getVersion());
    }
}
