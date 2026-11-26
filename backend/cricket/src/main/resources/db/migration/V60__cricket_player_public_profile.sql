-- A linked player (one with an account) decides whether their career statistics have a public page and are linked from public scorecards.
-- On by default; a guest (not linked to an account) never has a public page whatever this says, so the flag only matters after a claim is approved.
ALTER TABLE players ADD COLUMN public_profile BOOLEAN NOT NULL DEFAULT TRUE;
