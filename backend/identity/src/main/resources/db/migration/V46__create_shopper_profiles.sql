-- Shopper profiles: the account owner's own profile plus guardian-managed family member profiles, each with optional shopping
-- preferences. Family members are rows owned by the account (no credentials, no login) and are described by an age band, never a date of birth.
-- Deleting a profile deletes its preferences (hard delete = the retention rule).
CREATE TABLE shopper_profiles (
    id                      UUID PRIMARY KEY,
    owner_user_id           UUID          NOT NULL,
    kind                    VARCHAR(10)   NOT NULL,
    display_name            VARCHAR(60)   NOT NULL,
    relationship            VARCHAR(30),
    age_band                VARCHAR(12),
    sports                  TEXT          NOT NULL DEFAULT '[]',
    favorite_brands         TEXT          NOT NULL DEFAULT '[]',
    favorite_categories     TEXT          NOT NULL DEFAULT '[]',
    favorite_teams          TEXT          NOT NULL DEFAULT '[]',
    usage_level             VARCHAR(15),
    price_min               NUMERIC(12, 2),
    price_max               NUMERIC(12, 2),
    sizes                   TEXT          NOT NULL DEFAULT '{}',
    attributes              TEXT          NOT NULL DEFAULT '{}',
    personalization_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_shopper_profile_kind CHECK (kind IN ('OWNER', 'FAMILY')),
    CONSTRAINT ck_shopper_profile_age_band CHECK (age_band IS NULL OR age_band IN ('CHILD_UNDER_8', 'CHILD_8_12', 'TEEN_13_17', 'ADULT')),
    CONSTRAINT ck_shopper_profile_usage CHECK (usage_level IS NULL OR usage_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'PROFESSIONAL')),
    CONSTRAINT ck_shopper_profile_price CHECK (price_min IS NULL OR price_max IS NULL OR price_min <= price_max),
    CONSTRAINT ck_shopper_profile_price_positive CHECK ((price_min IS NULL OR price_min >= 0) AND (price_max IS NULL OR price_max >= 0))
);

CREATE UNIQUE INDEX uq_shopper_profile_owner ON shopper_profiles (owner_user_id) WHERE kind = 'OWNER';
CREATE INDEX ix_shopper_profiles_owner ON shopper_profiles (owner_user_id);

CREATE TABLE shopper_active_profile (
    owner_user_id UUID PRIMARY KEY,
    profile_id    UUID        NOT NULL REFERENCES shopper_profiles (id) ON DELETE CASCADE,
    updated_at    TIMESTAMPTZ NOT NULL
);
