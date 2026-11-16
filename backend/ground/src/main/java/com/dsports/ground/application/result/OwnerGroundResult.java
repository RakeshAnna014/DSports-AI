package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Ground;

/** A ground as its owner sees it: the public details plus the version to send back with an edit (so a stale edit is refused, not merged). */
public record OwnerGroundResult(GroundResult ground, int version) {

    public static OwnerGroundResult from(Ground g) {
        return new OwnerGroundResult(GroundResult.from(g), g.getVersion());
    }
}
