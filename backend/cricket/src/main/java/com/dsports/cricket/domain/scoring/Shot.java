package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

/**
 * Where a scoring ball went, as the scorer tapped it on a drawing of the field: {@code x} (right positive) and {@code y} (up positive, towards the bowler's end) in
 * thousandths of the field's radius from the middle of the pitch, so the point means the same on every screen. The point must be inside the field. It is display data only.
 */
public record Shot(int x, int y) {

    public static final int RADIUS = 1000;

    public Shot {
        if (Math.abs(x) > RADIUS || Math.abs(y) > RADIUS || (long) x * x + (long) y * y > (long) RADIUS * RADIUS) {
            throw new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "The shot must be inside the field");
        }
    }
}
