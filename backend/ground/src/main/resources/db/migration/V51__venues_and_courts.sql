-- Multi-sport venues (ADR-018). Forward-only: V48 is untouched. A venue is the place (address, owner, opening hours, photos); a court is
-- one bookable playing area at it (name, the sports it supports, slot length, prices); a booking is for one court and one chosen sport.
--
-- Existing rows are carried over safely: every ground becomes a venue with the SAME id, holding one court called "Main" that has the SAME id
-- too, supporting Cricket (when the catalogue has it). Bookings and blackouts are re-pointed at them. The old "grounds" table is left in place,
-- unused, as a rollback aid; a later migration can retire it.
--
-- Cross-module note: the sports live in the catalogue module, so a court stores sport ids WITHOUT a foreign key (the ground module's own
-- migrations must run without the catalogue tables). The application validates them against the catalogue.

CREATE TABLE venues (
    id             UUID PRIMARY KEY,
    owner_id       UUID           NOT NULL,
    name           VARCHAR(120)   NOT NULL,
    address        VARCHAR(300)   NOT NULL,
    city           VARCHAR(80)    NOT NULL,
    latitude       NUMERIC(9, 6),
    longitude      NUMERIC(9, 6),
    facilities     TEXT[]         NOT NULL DEFAULT '{}',
    photo_urls     TEXT[]         NOT NULL DEFAULT '{}',
    opens_at       TIME           NOT NULL,
    closes_at      TIME           NOT NULL,
    time_zone      VARCHAR(40)    NOT NULL DEFAULT 'Asia/Kolkata',
    currency       VARCHAR(3)     NOT NULL DEFAULT 'INR',
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    version        INTEGER        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_venues_hours CHECK (closes_at > opens_at),
    CONSTRAINT ck_venues_geo CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);

CREATE INDEX ix_venues_owner ON venues (owner_id);
CREATE INDEX ix_venues_city_active ON venues (lower(city)) WHERE active;

CREATE TABLE courts (
    id             UUID PRIMARY KEY,
    venue_id       UUID           NOT NULL REFERENCES venues (id),
    name           VARCHAR(80)    NOT NULL,
    surface        VARCHAR(40),
    sport_ids      UUID[]         NOT NULL DEFAULT '{}',
    slot_minutes   INTEGER        NOT NULL,
    base_price     NUMERIC(12, 2) NOT NULL,
    weekend_price  NUMERIC(12, 2),
    peak_price     NUMERIC(12, 2),
    peak_from      TIME,
    peak_to        TIME,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    version        INTEGER        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_courts_slot_minutes CHECK (slot_minutes BETWEEN 15 AND 240),
    CONSTRAINT ck_courts_prices CHECK (base_price >= 0 AND (weekend_price IS NULL OR weekend_price >= 0)
                                       AND (peak_price IS NULL OR peak_price >= 0)),
    CONSTRAINT ck_courts_peak_window CHECK (
        (peak_price IS NULL AND peak_from IS NULL AND peak_to IS NULL)
        OR (peak_price IS NOT NULL AND peak_from IS NOT NULL AND peak_to IS NOT NULL AND peak_to > peak_from)),
    CONSTRAINT ck_courts_sports_count CHECK (cardinality(sport_ids) <= 12)
);

CREATE UNIQUE INDEX uq_courts_venue_name ON courts (venue_id, lower(name));
CREATE INDEX ix_courts_venue ON courts (venue_id);
CREATE INDEX ix_courts_sports ON courts USING gin (sport_ids);

-- Carry the existing grounds over: venue id = ground id, court id = ground id.
INSERT INTO venues (id, owner_id, name, address, city, latitude, longitude, facilities, photo_urls, opens_at, closes_at, time_zone, currency,
                    active, version, created_at, updated_at)
SELECT id, owner_id, name, address, city, latitude, longitude, facilities, photo_urls, opens_at, closes_at, time_zone, currency,
       active, version, created_at, updated_at
FROM grounds;

DO $$
DECLARE
    cricket UUID;
BEGIN
    IF to_regclass('public.sports') IS NOT NULL THEN
        EXECUTE 'SELECT id FROM sports WHERE slug = ''cricket''' INTO cricket;
    END IF;

    INSERT INTO courts (id, venue_id, name, surface, sport_ids, slot_minutes, base_price, weekend_price, peak_price, peak_from, peak_to,
                        active, version, created_at, updated_at)
    SELECT g.id, g.id, 'Main', replace(lower(g.pitch_type), '_', ' '),
           CASE WHEN cricket IS NULL THEN '{}'::uuid[] ELSE ARRAY[cricket] END,
           g.slot_minutes, g.base_price, g.weekend_price, g.peak_price, g.peak_from, g.peak_to,
           g.active, g.version, g.created_at, g.updated_at
    FROM grounds g;
END
$$;

-- Bookings: the court is what cannot be double booked, the sport is what the customer chose to play on it.
ALTER TABLE ground_bookings ADD COLUMN court_id UUID;
ALTER TABLE ground_bookings ADD COLUMN sport_id UUID;
UPDATE ground_bookings SET court_id = ground_id;
UPDATE ground_bookings b SET sport_id = c.sport_ids[1] FROM courts c WHERE c.id = b.court_id;

ALTER TABLE ground_bookings RENAME COLUMN ground_id TO venue_id;
ALTER TABLE ground_bookings ALTER COLUMN court_id SET NOT NULL;
ALTER TABLE ground_bookings ALTER COLUMN sport_id SET NOT NULL;
ALTER TABLE ground_bookings DROP CONSTRAINT ground_bookings_ground_id_fkey;
ALTER TABLE ground_bookings ADD CONSTRAINT fk_ground_bookings_venue FOREIGN KEY (venue_id) REFERENCES venues (id);
ALTER TABLE ground_bookings ADD CONSTRAINT fk_ground_bookings_court FOREIGN KEY (court_id) REFERENCES courts (id);

-- No double booking, enforced by the database, now per COURT: one court can never hold two active bookings that overlap, whatever the sport;
-- different courts of one venue can be booked at the same time. Half-open ranges, so back-to-back slots are allowed.
ALTER TABLE ground_bookings DROP CONSTRAINT no_overlap;
ALTER TABLE ground_bookings ADD CONSTRAINT no_overlap_court EXCLUDE USING gist (
    court_id WITH =,
    tstzrange(start_at, end_at, '[)') WITH &&
) WHERE (status IN ('HELD', 'CONFIRMED'));

CREATE INDEX ix_ground_bookings_court_start ON ground_bookings (court_id, start_at);

-- Blackouts close the whole venue (court_id NULL) or one court.
ALTER TABLE ground_blackouts RENAME COLUMN ground_id TO venue_id;
ALTER TABLE ground_blackouts ADD COLUMN court_id UUID REFERENCES courts (id);
ALTER TABLE ground_blackouts DROP CONSTRAINT ground_blackouts_ground_id_fkey;
ALTER TABLE ground_blackouts ADD CONSTRAINT fk_ground_blackouts_venue FOREIGN KEY (venue_id) REFERENCES venues (id);
CREATE INDEX ix_ground_blackouts_court_start ON ground_blackouts (court_id, start_at) WHERE court_id IS NOT NULL;
