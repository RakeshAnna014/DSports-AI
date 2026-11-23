package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.exception.CricketDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShotTest {

    @Test
    void aPointInsideTheFieldIsAcceptedIncludingTheCentreAndTheEdge() {
        assertThat(new Shot(0, 0)).isNotNull();
        assertThat(new Shot(1000, 0)).isNotNull();
        assertThat(new Shot(-600, 800)).as("exactly on the boundary rope").isNotNull();
    }

    @Test
    void aPointOutsideTheFieldIsRefusedEvenInsideTheBoundingSquare() {
        for (int[] p : new int[][] {{1001, 0}, {0, -1001}, {800, 800}, {-710, -710}, {Integer.MAX_VALUE, 0}, {Integer.MIN_VALUE, 5}}) {
            assertThatThrownBy(() -> new Shot(p[0], p[1])).as("%d,%d", p[0], p[1]).isInstanceOf(CricketDomainException.class);
        }
    }
}
