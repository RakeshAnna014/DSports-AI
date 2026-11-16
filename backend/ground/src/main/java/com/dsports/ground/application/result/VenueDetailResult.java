package com.dsports.ground.application.result;

import java.util.List;

/**
 * A venue with its active courts, for the public venue page. {@code acceptingBookings} is false while the venue's owner no longer holds the
 * ground-owner role: the page can say so instead of offering slots that would be refused.
 */
public record VenueDetailResult(VenueResult venue, List<CourtResult> courts, boolean acceptingBookings) {
}
