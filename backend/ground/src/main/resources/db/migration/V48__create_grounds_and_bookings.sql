-- Cricket ground booking (Phase 1). Forward-only. Money is NUMERIC(12,2) rupees like the rest of DSports.
-- Opening hours are a local wall-clock time plus a time zone on the ground; only bookings and blackouts are absolute instants.
-- Available slots are computed from these rows, never stored.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SEQUENCE ground_booking_seq START 1;

CREATE TABLE grounds (
    id             UUID PRIMARY KEY,
    owner_id       UUID           NOT NULL,
    name           VARCHAR(120)   NOT NULL,
    address        VARCHAR(300)   NOT NULL,
    city           VARCHAR(80)    NOT NULL,
    latitude       NUMERIC(9, 6),
    longitude      NUMERIC(9, 6),
    pitch_type     VARCHAR(20)    NOT NULL,
    facilities     TEXT[]         NOT NULL DEFAULT '{}',
    photo_urls     TEXT[]         NOT NULL DEFAULT '{}',
    opens_at       TIME           NOT NULL,
    closes_at      TIME           NOT NULL,
    time_zone      VARCHAR(40)    NOT NULL DEFAULT 'Asia/Kolkata',
    slot_minutes   INTEGER        NOT NULL,
    base_price     NUMERIC(12, 2) NOT NULL,
    weekend_price  NUMERIC(12, 2),
    peak_price     NUMERIC(12, 2),
    peak_from      TIME,
    peak_to        TIME,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'INR',
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    version        INTEGER        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_grounds_pitch_type CHECK (pitch_type IN ('TURF', 'MATTING', 'CONCRETE', 'NATURAL_GRASS', 'INDOOR')),
    CONSTRAINT ck_grounds_hours CHECK (closes_at > opens_at),
    CONSTRAINT ck_grounds_slot_minutes CHECK (slot_minutes BETWEEN 15 AND 240),
    CONSTRAINT ck_grounds_prices CHECK (base_price >= 0 AND (weekend_price IS NULL OR weekend_price >= 0)
                                        AND (peak_price IS NULL OR peak_price >= 0)),
    CONSTRAINT ck_grounds_peak_window CHECK (
        (peak_price IS NULL AND peak_from IS NULL AND peak_to IS NULL)
        OR (peak_price IS NOT NULL AND peak_from IS NOT NULL AND peak_to IS NOT NULL AND peak_to > peak_from)),
    CONSTRAINT ck_grounds_geo CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);

CREATE INDEX ix_grounds_owner ON grounds (owner_id);
CREATE INDEX ix_grounds_city_active ON grounds (lower(city)) WHERE active;

CREATE TABLE ground_blackouts (
    id          UUID PRIMARY KEY,
    ground_id   UUID         NOT NULL REFERENCES grounds (id),
    start_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_ground_blackouts_period CHECK (end_at > start_at)
);

CREATE INDEX ix_ground_blackouts_ground_start ON ground_blackouts (ground_id, start_at);

CREATE TABLE ground_bookings (
    id               UUID PRIMARY KEY,
    booking_number   VARCHAR(30)    NOT NULL,
    ground_id        UUID           NOT NULL REFERENCES grounds (id),
    user_id          UUID           NOT NULL,
    start_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    price            NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    status           VARCHAR(12)    NOT NULL,
    payment_id       UUID,
    idempotency_key  VARCHAR(100)   NOT NULL,
    held_until       TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at     TIMESTAMP WITH TIME ZONE,
    cancelled_by     VARCHAR(10),
    version          INTEGER        NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_ground_bookings_status CHECK (status IN ('HELD', 'CONFIRMED', 'EXPIRED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT ck_ground_bookings_period CHECK (end_at > start_at),
    CONSTRAINT ck_ground_bookings_price CHECK (price >= 0),
    CONSTRAINT ck_ground_bookings_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('CUSTOMER', 'OWNER')),
    CONSTRAINT uq_ground_bookings_number UNIQUE (booking_number),
    CONSTRAINT uq_ground_bookings_user_key UNIQUE (user_id, idempotency_key),

    -- No double booking, enforced by the database: two active bookings of one ground can never overlap, however the requests interleave.
    -- Half-open ranges, so back-to-back slots (one ends exactly when the next starts) are allowed.
    CONSTRAINT no_overlap EXCLUDE USING gist (
        ground_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('HELD', 'CONFIRMED'))
);

CREATE INDEX ix_ground_bookings_user ON ground_bookings (user_id, created_at DESC);
CREATE INDEX ix_ground_bookings_ground_start ON ground_bookings (ground_id, start_at);
CREATE INDEX ix_ground_bookings_held_until ON ground_bookings (held_until) WHERE status = 'HELD';
