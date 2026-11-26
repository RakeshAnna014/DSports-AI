-- Cricket, step 2a (ADR-019): players (guests allowed, optionally linked to a registered customer), teams and their members, and guest-profile claims.
-- Forward-only. No foreign keys to other modules' tables (customers live in the identity module).
--
-- A phone number is a HINT, never an identity proof: customers.phone is unverified and there is no OTP. A guest is linked to a customer only
-- when the guest's creator (or an administrator) approves a claim. Phones are unique per creator, not globally, so two captains can each add
-- the same real person as a guest.

CREATE TABLE players (
    id            UUID PRIMARY KEY,
    name          VARCHAR(80)  NOT NULL,
    phone         VARCHAR(16),
    playing_role  VARCHAR(20),
    batting_hand  VARCHAR(5),
    bowling_style VARCHAR(40),
    photo_url     VARCHAR(500),
    customer_id   UUID,
    created_by    UUID         NOT NULL,
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_players_role CHECK (playing_role IS NULL OR playing_role IN ('BATTER', 'BOWLER', 'ALL_ROUNDER', 'WICKET_KEEPER')),
    CONSTRAINT ck_players_hand CHECK (batting_hand IS NULL OR batting_hand IN ('RIGHT', 'LEFT')),
    CONSTRAINT ck_players_name CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX uq_players_creator_phone ON players (created_by, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_players_customer ON players (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_players_created_by ON players (created_by);
CREATE INDEX ix_players_phone_unlinked ON players (phone) WHERE phone IS NOT NULL AND customer_id IS NULL;

CREATE TABLE teams (
    id          UUID PRIMARY KEY,
    name        VARCHAR(60)  NOT NULL,
    city        VARCHAR(80),
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_by  UUID         NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_teams_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_teams_name CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX uq_teams_creator_name ON teams (created_by, lower(name));

CREATE TABLE team_members (
    team_id    UUID    NOT NULL REFERENCES teams (id),
    player_id  UUID    NOT NULL REFERENCES players (id),
    captain    BOOLEAN NOT NULL DEFAULT FALSE,
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (team_id, player_id)
);

-- At most one captain per team, enforced by the database.
CREATE UNIQUE INDEX uq_team_one_captain ON team_members (team_id) WHERE captain;
CREATE INDEX ix_team_members_player ON team_members (player_id);

CREATE TABLE player_claims (
    id           UUID PRIMARY KEY,
    player_id    UUID        NOT NULL REFERENCES players (id),
    claimant_id  UUID        NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    decided_by   UUID,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    decided_at   TIMESTAMP WITH TIME ZONE,

    CONSTRAINT ck_player_claims_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_player_claims_decided CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX uq_player_claims_open ON player_claims (player_id, claimant_id) WHERE status = 'PENDING';
CREATE INDEX ix_player_claims_claimant ON player_claims (claimant_id, created_at DESC);
CREATE INDEX ix_player_claims_player ON player_claims (player_id, status);
