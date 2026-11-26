package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.SportCatalogPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns bookings into what people read: the venue, court and sport names, and the zone to show times in. Every lookup is optional: a venue,
 * court or sport that no longer resolves leaves its name null, it never fails the request. One place, so every use case presents a booking the same way.
 */
public class BookingPresenter {

    /** What a booking refers to, resolved. Any member may be null. */
    public record Context(Venue venue, Court court, String sportName) {

        public BookingResult.Names names() {
            return new BookingResult.Names(venue == null ? null : venue.getName(), venue == null ? null : venue.getCity(),
                court == null ? null : court.getName(), sportName);
        }

        public ZoneId zone() {
            return venue == null ? ZoneOffset.UTC : venue.getTimeZone();
        }

        /** A short label for messages: "Venue, Court (Sport)". */
        public String label() {
            if (venue == null) {
                return "your venue";
            }
            var label = new StringBuilder(venue.getName());
            if (court != null) {
                label.append(", ").append(court.getName());
            }
            if (sportName != null) {
                label.append(" (").append(sportName).append(')');
            }
            return label.toString();
        }
    }

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final SportCatalogPort sports;

    public BookingPresenter(VenueRepository venues, CourtRepository courts, SportCatalogPort sports) {
        this.venues = venues;
        this.courts = courts;
        this.sports = sports;
    }

    public Mono<Context> context(Booking booking) {
        return presentAllContexts(List.of(booking)).map(m -> m.get(booking.getId()));
    }

    public Mono<BookingResult> present(Booking booking) {
        return context(booking).map(c -> BookingResult.from(booking, c.names()));
    }

    /** Presents many bookings with three lookups in total, not three per booking. */
    public Mono<List<BookingResult>> presentAll(Collection<Booking> bookings) {
        return presentAllContexts(bookings)
            .map(byBooking -> bookings.stream().map(b -> BookingResult.from(b, byBooking.get(b.getId()).names())).toList());
    }

    private Mono<Map<UUID, Context>> presentAllContexts(Collection<Booking> bookings) {
        var venueIds = bookings.stream().map(Booking::getVenueId).collect(Collectors.toSet());
        var courtIds = bookings.stream().map(Booking::getCourtId).collect(Collectors.toSet());
        var sportIds = bookings.stream().map(Booking::getSportId).collect(Collectors.toSet());
        return Mono.zip(
                venueIds.isEmpty() ? Mono.just(Map.<UUID, Venue>of()) : venues.findAllById(venueIds).collectMap(Venue::getId, Function.identity()),
                courtIds.isEmpty() ? Mono.just(Map.<UUID, Court>of()) : courts.findAllById(courtIds).collectMap(Court::getId, Function.identity()),
                sportIds.isEmpty() ? Mono.just(Map.<UUID, String>of()) : sports.findByIds(sportIds).collectMap(SportCatalogPort.SportRef::id, SportCatalogPort.SportRef::name))
            .map(t -> {
                var result = new HashMap<UUID, Context>();
                for (var b : bookings) {
                    result.put(b.getId(), new Context(t.getT1().get(b.getVenueId()), t.getT2().get(b.getCourtId()), t.getT3().get(b.getSportId())));
                }
                return result;
            });
    }
}
