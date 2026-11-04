package com.dsports.review.domain.model;

import com.dsports.review.domain.exception.ReviewDomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RatingTest {

    @Test
    void shouldCreateValidRating() {
        var rating = Rating.of(3);
        assertEquals(3, rating.value());
    }

    @Test
    void shouldRejectRatingBelowMinimum() {
        assertThrows(ReviewDomainException.class, () -> Rating.of(0));
    }

    @Test
    void shouldRejectRatingAboveMaximum() {
        assertThrows(ReviewDomainException.class, () -> Rating.of(6));
    }

    @Test
    void shouldAcceptBoundaryRatings() {
        assertDoesNotThrow(() -> Rating.of(1));
        assertDoesNotThrow(() -> Rating.of(5));
    }
}
