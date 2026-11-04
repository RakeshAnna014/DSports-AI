-- Product & Supplier Intelligence (Phase 5). Owned by the AI service; commerce data (catalogue, prices, orders) stays in the Java backend and is only READ through its REST API.
-- Every opportunity, supplier and quote carries `origin`:
--   LIVE_RESEARCH  found by a configured, permitted source adapter          DEMAND_SIGNAL  derived from customer searches that found nothing
--   MANUAL         entered by a buyer                                        DEMO           clearly labelled local demonstration data, never real research
-- Quotes additionally carry `source_kind` (PUBLIC_LISTING / QUOTE_ENTERED / VERIFIED / AI_ESTIMATE). Rebuildable demo rows: DELETE ... WHERE origin = 'DEMO'.

CREATE TABLE IF NOT EXISTS src_runs (
    id            UUID PRIMARY KEY,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT        NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    requested_by  TEXT        NOT NULL,
    params        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    params_hash   TEXT        NOT NULL,
    sources       JSONB       NOT NULL DEFAULT '[]'::jsonb,      -- per provider: name, status (ok|unavailable|error), records, reason
    stats         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    error         TEXT
);
CREATE INDEX IF NOT EXISTS ix_src_runs_started ON src_runs (started_at DESC);
-- At most one RUNNING run per identical request: a double click or a retry cannot start the same research twice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_src_runs_running ON src_runs (params_hash) WHERE status = 'RUNNING';

CREATE TABLE IF NOT EXISTS src_records (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID        REFERENCES src_runs (id) ON DELETE SET NULL,
    provider     TEXT        NOT NULL,
    url          TEXT        NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    content_hash TEXT        NOT NULL,
    excerpt      TEXT,                                            -- a short sanitised excerpt, never the whole page
    structured   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_src_record UNIQUE (provider, url, content_hash)
);

CREATE TABLE IF NOT EXISTS src_opportunities (
    id               UUID PRIMARY KEY,
    identity_key     TEXT        NOT NULL,
    name             TEXT        NOT NULL,
    brand            TEXT,
    manufacturer     TEXT,
    model            TEXT,
    gtin             TEXT,
    sport            TEXT,
    category         TEXT,
    product_type     TEXT,
    segment          TEXT,
    description      TEXT,
    specs            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    trend_note       TEXT,
    relevance        TEXT,
    status           TEXT        NOT NULL DEFAULT 'DISCOVERED' CHECK (status IN ('DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'SUPPLIER_RESEARCH', 'QUOTE_COMPARISON',
                                                                              'APPROVED_FOR_ONBOARDING', 'REJECTED', 'ON_HOLD', 'NEEDS_MORE_EVIDENCE')),
    origin           TEXT        NOT NULL CHECK (origin IN ('LIVE_RESEARCH', 'DEMAND_SIGNAL', 'MANUAL', 'DEMO')),
    catalogue_match  JSONB       NOT NULL DEFAULT '{}'::jsonb,     -- {level: EXACT|POSSIBLE|NONE, reason, product_id, score}
    possible_dupes   JSONB       NOT NULL DEFAULT '[]'::jsonb,     -- near-duplicates awaiting a buyer's decision (never auto-merged)
    selling_price    NUMERIC(14, 2),                               -- the buyer's intended selling price (tax treatment kept with the calculation)
    unanswered       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    first_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_verified    TIMESTAMPTZ,
    created_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT uq_src_identity UNIQUE (identity_key, origin)
);
CREATE INDEX IF NOT EXISTS ix_src_opp_status ON src_opportunities (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_src_opp_sport ON src_opportunities (sport, category);

CREATE TABLE IF NOT EXISTS src_evidence (
    id             BIGSERIAL PRIMARY KEY,
    opportunity_id UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    record_id      BIGINT      REFERENCES src_records (id) ON DELETE SET NULL,
    kind           TEXT        NOT NULL CHECK (kind IN ('SOURCE_RECORD', 'CUSTOMER_DEMAND', 'INVENTORY_SIGNAL', 'BUYER_NOTE', 'CATALOGUE')),
    source_label   TEXT        NOT NULL,
    url            TEXT,
    claim          TEXT        NOT NULL,                           -- the single fact this evidence supports
    excerpt        TEXT,
    retrieved_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at    TIMESTAMPTZ,
    conflicts_with BIGINT[]    NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS ix_src_evidence_opp ON src_evidence (opportunity_id);

CREATE TABLE IF NOT EXISTS src_suppliers (
    id                  UUID PRIMARY KEY,
    name                TEXT        NOT NULL,
    type                TEXT        NOT NULL CHECK (type IN ('MANUFACTURER', 'AUTHORISED_DISTRIBUTOR', 'WHOLESALER', 'REGIONAL_DISTRIBUTOR', 'IMPORTER', 'DIRECT_SUPPLIER')),
    website             TEXT,
    contact             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    country             TEXT,
    region              TEXT,
    categories          TEXT[]      NOT NULL DEFAULT '{}',
    brands              TEXT[]      NOT NULL DEFAULT '{}',
    authorised_brands   TEXT[]      NOT NULL DEFAULT '{}',        -- only brands for which authorisation evidence was recorded
    authorisation_note  TEXT,
    verification_status TEXT        NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED', 'DOCUMENT_SEEN', 'VERIFIED')),
    origin              TEXT        NOT NULL CHECK (origin IN ('LIVE_RESEARCH', 'MANUAL', 'DEMO')),
    created_by          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_src_supplier_name ON src_suppliers (lower(name), origin);

CREATE TABLE IF NOT EXISTS src_quotes (
    id              UUID PRIMARY KEY,
    opportunity_id  UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    supplier_id     UUID        NOT NULL REFERENCES src_suppliers (id),
    equivalence     TEXT        NOT NULL DEFAULT 'IDENTICAL' CHECK (equivalence IN ('IDENTICAL', 'EQUIVALENT_ALTERNATIVE')),
    equivalence_note TEXT,
    offered_product TEXT        NOT NULL,
    model           TEXT,
    currency        TEXT        NOT NULL,
    unit_price      NUMERIC(14, 4) NOT NULL CHECK (unit_price > 0),
    moq             INTEGER CHECK (moq IS NULL OR moq > 0),
    price_breaks    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    shipping_cost   NUMERIC(14, 2) CHECK (shipping_cost IS NULL OR shipping_cost >= 0),   -- in the quote currency, for the quoted quantity basis stated in `shipping_note`
    shipping_note   TEXT,
    other_costs     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    lead_time_days  INTEGER CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    availability    TEXT,
    warranty        TEXT,
    return_terms    TEXT,
    certifications  TEXT,
    payment_terms   TEXT,
    quoted_on       DATE,
    valid_until     DATE,
    source_kind     TEXT        NOT NULL CHECK (source_kind IN ('PUBLIC_LISTING', 'QUOTE_ENTERED', 'VERIFIED', 'AI_ESTIMATE')),
    source_url      TEXT,
    reference       TEXT,                                          -- the supplier's own quotation number, when there is one
    origin          TEXT        NOT NULL CHECK (origin IN ('LIVE_RESEARCH', 'MANUAL', 'DEMO')),
    entered_by      TEXT        NOT NULL,
    verified_by     TEXT,
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_src_quote_window CHECK (valid_until IS NULL OR quoted_on IS NULL OR valid_until >= quoted_on)
);
CREATE INDEX IF NOT EXISTS ix_src_quotes_opp ON src_quotes (opportunity_id);

CREATE TABLE IF NOT EXISTS src_calcs (
    id             UUID PRIMARY KEY,
    opportunity_id UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    quote_id       UUID        REFERENCES src_quotes (id) ON DELETE SET NULL,
    inputs         JSONB       NOT NULL,
    result         JSONB       NOT NULL,
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_src_calcs_opp ON src_calcs (opportunity_id, created_at DESC);

CREATE TABLE IF NOT EXISTS src_scores (
    id             BIGSERIAL PRIMARY KEY,
    opportunity_id UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    model_version  TEXT        NOT NULL,
    result         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_src_scores_opp ON src_scores (opportunity_id, created_at DESC);

-- Append-only decision and audit history.
CREATE TABLE IF NOT EXISTS src_decisions (
    id             BIGSERIAL PRIMARY KEY,
    opportunity_id UUID        REFERENCES src_opportunities (id) ON DELETE CASCADE,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_hash     TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    from_status    TEXT,
    to_status      TEXT,
    reason         TEXT,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS ix_src_decisions_opp ON src_decisions (opportunity_id, at);

CREATE TABLE IF NOT EXISTS src_proposals (
    id             UUID PRIMARY KEY,
    opportunity_id UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    status         TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'READY_FOR_REVIEW', 'REVIEWED')),
    payload        JSONB       NOT NULL,
    validation     JSONB       NOT NULL,
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_src_proposals_opp ON src_proposals (opportunity_id, created_at DESC);

-- Customer Assistant searches that found nothing (or almost nothing). Minimal and privacy-preserving: a salted hash of the customer, the redacted query
-- (no digits, e-mail addresses or long tokens, at most 80 characters), the result count. No conversation text, no names.
CREATE TABLE IF NOT EXISTS src_demand_signals (
    id            BIGSERIAL PRIMARY KEY,
    ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
    customer_hash TEXT        NOT NULL,
    query_norm    TEXT        NOT NULL,
    sport         TEXT,
    result_count  INTEGER     NOT NULL,
    kind          TEXT        NOT NULL CHECK (kind IN ('ZERO_RESULTS', 'LOW_RESULTS'))
);
-- The distinctive term that made the search unmet (the longest term unknown to the catalogue). Variants of one request ("pickleball paddles", "pickleball") share it, so they count as ONE demand.
ALTER TABLE src_demand_signals ADD COLUMN IF NOT EXISTS key_term TEXT;
CREATE INDEX IF NOT EXISTS ix_src_demand_key ON src_demand_signals (key_term, ts DESC);
CREATE INDEX IF NOT EXISTS ix_src_demand_ts ON src_demand_signals (ts DESC);
CREATE INDEX IF NOT EXISTS ix_src_demand_query ON src_demand_signals (query_norm, ts DESC);

-- ====================================================================================================================================================================
-- Data-quality overhaul (Phase 5 correction). Additive and idempotent: existing rows are preserved and classified, never deleted.
-- PROVENANCE says where a record really came from; every KPI, list and score uses it, so demonstration and test fixtures can never pass as live discoveries.
--   LIVE_EXTERNAL_SOURCE      fetched by a configured source adapter, with the source URL stored as evidence
--   BUYER_ENTERED             entered by a buyer through the API/UI
--   CUSTOMER_DEMAND_SIGNAL    derived from what customers searched for and did not find (interest, not a product)
--   DEMO_FIXTURE              labelled demonstration data (POST /demo/seed)
--   TEST_FIXTURE              created by automated tests/scripts; kept so tests are reproducible, excluded from live metrics
--   UNKNOWN_PROVENANCE        legacy or unclassifiable: excluded from claims about verified discovery until a buyer reviews it
-- ====================================================================================================================================================================
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS provenance TEXT;
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS record_kind TEXT NOT NULL DEFAULT 'PRODUCT_CANDIDATE';   -- PRODUCT_CANDIDATE | DEMAND_GAP (a search term, not a product)
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS source_title TEXT;           -- exactly what the source page called it
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS model_name TEXT;
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS mfr_sku TEXT;                -- manufacturer SKU / part number
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS variant JSONB NOT NULL DEFAULT '{}'::jsonb;   -- size, colour, weight class, ...
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS product_url TEXT;
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ;
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS approved_quote_id UUID;      -- the offer a buyer chose when approving for onboarding preparation
ALTER TABLE src_opportunities ADD COLUMN IF NOT EXISTS linked_candidate_id UUID;    -- DEMAND_GAP only: the real product candidate a buyer linked it to

UPDATE src_opportunities SET provenance = CASE
    WHEN origin = 'DEMO' THEN 'DEMO_FIXTURE'
    WHEN name ILIKE 'E2E %' OR brand ILIKE 'e2e%' OR identity_key ILIKE 'demand:zorb%' OR identity_key ILIKE 'bm:e2e%' OR name ILIKE 'zorb%' THEN 'TEST_FIXTURE'
    WHEN origin = 'DEMAND_SIGNAL' THEN 'CUSTOMER_DEMAND_SIGNAL'
    WHEN origin = 'LIVE_RESEARCH' AND EXISTS (SELECT 1 FROM src_evidence e WHERE e.opportunity_id = src_opportunities.id AND e.url IS NOT NULL) THEN 'LIVE_EXTERNAL_SOURCE'
    WHEN origin = 'MANUAL' THEN 'BUYER_ENTERED'
    ELSE 'UNKNOWN_PROVENANCE' END
WHERE provenance IS NULL;
UPDATE src_opportunities SET record_kind = 'DEMAND_GAP' WHERE origin = 'DEMAND_SIGNAL' AND record_kind = 'PRODUCT_CANDIDATE';
UPDATE src_opportunities SET last_seen = COALESCE(last_verified, created_at) WHERE last_seen IS NULL;
ALTER TABLE src_opportunities ALTER COLUMN provenance SET NOT NULL;
ALTER TABLE src_opportunities ALTER COLUMN provenance SET DEFAULT 'UNKNOWN_PROVENANCE';

DO $$
DECLARE c TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_src_opp_provenance') THEN
        ALTER TABLE src_opportunities ADD CONSTRAINT ck_src_opp_provenance CHECK (provenance IN ('LIVE_EXTERNAL_SOURCE', 'BUYER_ENTERED', 'CUSTOMER_DEMAND_SIGNAL', 'DEMO_FIXTURE', 'TEST_FIXTURE', 'UNKNOWN_PROVENANCE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_src_opp_kind') THEN
        ALTER TABLE src_opportunities ADD CONSTRAINT ck_src_opp_kind CHECK (record_kind IN ('PRODUCT_CANDIDATE', 'DEMAND_GAP'));
    END IF;
    -- a new status: APPROVED_OPPORTUNITY = approved as a business opportunity, BEFORE any supplier work (APPROVED_FOR_ONBOARDING = approved to prepare sourcing and onboarding)
    SELECT conname INTO c FROM pg_constraint WHERE conrelid = 'src_opportunities'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%DISCOVERED%' AND pg_get_constraintdef(oid) LIKE '%status%' AND pg_get_constraintdef(oid) NOT LIKE '%APPROVED_OPPORTUNITY%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE src_opportunities DROP CONSTRAINT %I', c);
        ALTER TABLE src_opportunities ADD CONSTRAINT ck_src_opp_status CHECK (status IN ('DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'APPROVED_OPPORTUNITY', 'SUPPLIER_RESEARCH', 'QUOTE_COMPARISON',
                                                                                  'APPROVED_FOR_ONBOARDING', 'REJECTED', 'ON_HOLD', 'NEEDS_MORE_EVIDENCE'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_src_opp_provenance ON src_opportunities (provenance, record_kind, status);
CREATE INDEX IF NOT EXISTS ix_src_opp_sku ON src_opportunities (lower(mfr_sku)) WHERE mfr_sku IS NOT NULL;

ALTER TABLE src_suppliers ADD COLUMN IF NOT EXISTS provenance TEXT;
ALTER TABLE src_suppliers ADD COLUMN IF NOT EXISTS discovery_url TEXT;
ALTER TABLE src_suppliers ADD COLUMN IF NOT EXISTS discovery_note TEXT;
ALTER TABLE src_suppliers ADD COLUMN IF NOT EXISTS discovered_at TIMESTAMPTZ;
ALTER TABLE src_suppliers ADD COLUMN IF NOT EXISTS verification_history JSONB NOT NULL DEFAULT '[]'::jsonb;
UPDATE src_suppliers SET provenance = CASE
    WHEN origin = 'DEMO' THEN 'DEMO_FIXTURE'
    WHEN name ILIKE 'E2E %' OR name ILIKE 'DEMO %' OR name ILIKE 'test %' THEN 'TEST_FIXTURE'
    WHEN origin = 'LIVE_RESEARCH' THEN 'LIVE_EXTERNAL_SOURCE'
    WHEN origin = 'MANUAL' THEN 'BUYER_ENTERED'
    ELSE 'UNKNOWN_PROVENANCE' END
WHERE provenance IS NULL;
ALTER TABLE src_suppliers ALTER COLUMN provenance SET NOT NULL;
ALTER TABLE src_suppliers ALTER COLUMN provenance SET DEFAULT 'UNKNOWN_PROVENANCE';
DO $$
DECLARE c TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_src_sup_provenance') THEN
        ALTER TABLE src_suppliers ADD CONSTRAINT ck_src_sup_provenance CHECK (provenance IN ('LIVE_EXTERNAL_SOURCE', 'BUYER_ENTERED', 'CUSTOMER_DEMAND_SIGNAL', 'DEMO_FIXTURE', 'TEST_FIXTURE', 'UNKNOWN_PROVENANCE'));
    END IF;
    -- verification is a ladder, not a flag: DISCOVERED (seen in a public source) < WEBSITE_CHECKED < IDENTITY_VERIFIED < BRAND_AUTHORISATION_VERIFIED. A website or a listing does not make a supplier reliable.
    SELECT conname INTO c FROM pg_constraint WHERE conrelid = 'src_suppliers'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%verification_status%' AND pg_get_constraintdef(oid) NOT LIKE '%IDENTITY_VERIFIED%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE src_suppliers DROP CONSTRAINT %I', c);
        UPDATE src_suppliers SET verification_status = CASE verification_status WHEN 'UNVERIFIED' THEN 'DISCOVERED' WHEN 'DOCUMENT_SEEN' THEN 'WEBSITE_CHECKED' WHEN 'VERIFIED' THEN 'IDENTITY_VERIFIED' ELSE verification_status END;
        ALTER TABLE src_suppliers ADD CONSTRAINT ck_src_sup_verification CHECK (verification_status IN ('DISCOVERED', 'WEBSITE_CHECKED', 'IDENTITY_VERIFIED', 'BRAND_AUTHORISATION_VERIFIED'));
        ALTER TABLE src_suppliers ALTER COLUMN verification_status SET DEFAULT 'DISCOVERED';
    END IF;
END $$;

ALTER TABLE src_quotes ADD COLUMN IF NOT EXISTS provenance TEXT;
UPDATE src_quotes q SET provenance = CASE
    WHEN s.provenance IN ('TEST_FIXTURE', 'DEMO_FIXTURE') THEN s.provenance
    WHEN o.provenance IN ('TEST_FIXTURE', 'DEMO_FIXTURE') THEN o.provenance
    WHEN q.origin = 'DEMO' THEN 'DEMO_FIXTURE'
    WHEN q.origin = 'LIVE_RESEARCH' THEN 'LIVE_EXTERNAL_SOURCE'
    WHEN q.origin = 'MANUAL' THEN 'BUYER_ENTERED'
    ELSE 'UNKNOWN_PROVENANCE' END
FROM src_suppliers s, src_opportunities o WHERE s.id = q.supplier_id AND o.id = q.opportunity_id AND q.provenance IS NULL;
ALTER TABLE src_quotes ALTER COLUMN provenance SET NOT NULL;
ALTER TABLE src_quotes ALTER COLUMN provenance SET DEFAULT 'UNKNOWN_PROVENANCE';

ALTER TABLE src_evidence ADD COLUMN IF NOT EXISTS source_type TEXT;                  -- OFFICIAL_BRAND_PAGE | AUTHORISED_DISTRIBUTOR | PUBLIC_LISTING | CUSTOMER_SEARCHES | BUYER_NOTE | CATALOGUE

-- Product images are REFERENCES with provenance (no file is downloaded or redistributed). match_level says how sure we are the image shows this product.
CREATE TABLE IF NOT EXISTS src_images (
    id              UUID PRIMARY KEY,
    opportunity_id  UUID        NOT NULL REFERENCES src_opportunities (id) ON DELETE CASCADE,
    url             TEXT        NOT NULL,
    source_page_url TEXT,
    source_type     TEXT        NOT NULL CHECK (source_type IN ('OFFICIAL_BRAND_PAGE', 'AUTHORISED_DISTRIBUTOR', 'PUBLIC_LISTING', 'FEED', 'BUYER_ENTERED')),
    match_level     TEXT        NOT NULL CHECK (match_level IN ('EXACT_MODEL', 'LIKELY_MATCH', 'SIMILAR_REFERENCE', 'UNVERIFIED')),
    attribution     TEXT,
    rights_note     TEXT,
    status          TEXT        NOT NULL DEFAULT 'UNCHECKED' CHECK (status IN ('VALID', 'BROKEN', 'UNCHECKED', 'BLOCKED', 'UNSUPPORTED')),
    status_detail   TEXT,
    content_type    TEXT,
    content_length  BIGINT,
    checked_at      TIMESTAMPTZ,
    is_primary      BOOLEAN     NOT NULL DEFAULT FALSE,
    position        INTEGER     NOT NULL DEFAULT 0,
    reported        BOOLEAN     NOT NULL DEFAULT FALSE,
    reported_reason TEXT,
    reported_by     TEXT,
    added_by        TEXT        NOT NULL,
    retrieved_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_src_image UNIQUE (opportunity_id, url)
);
CREATE INDEX IF NOT EXISTS ix_src_images_opp ON src_images (opportunity_id, position);

ALTER TABLE src_calcs ADD COLUMN IF NOT EXISTS basis JSONB NOT NULL DEFAULT '{}'::jsonb;   -- the product, offer, quantity, currency and prices this number was calculated for

-- ====================================================================================================================================================================
-- RECONCILIATION of rows written by earlier versions (idempotent; nothing is deleted, every correction is audited in src_decisions)
-- ====================================================================================================================================================================
-- (1) Rows created by the repository's own end-to-end scripts (their customers search for made-up "zorb..." words) are test data, even when a later run wrote them as customer demand.
UPDATE src_opportunities SET provenance = 'TEST_FIXTURE'
 WHERE provenance = 'CUSTOMER_DEMAND_SIGNAL' AND (name ILIKE 'zorb%' OR identity_key ILIKE 'demand:zorb%');

-- (2) A demand gap is a search term, not a product: it can be reviewed, held or dismissed but never shortlisted, approved, quoted or sent to onboarding. Gaps that an earlier version moved
--     into those product-only states are returned to UNDER_REVIEW (their earlier state stays visible in the decision history).
WITH legacy AS (
    SELECT id, status FROM src_opportunities
     WHERE record_kind = 'DEMAND_GAP' AND status IN ('SHORTLISTED', 'APPROVED_OPPORTUNITY', 'SUPPLIER_RESEARCH', 'QUOTE_COMPARISON', 'APPROVED_FOR_ONBOARDING')
), moved AS (
    UPDATE src_opportunities o SET status = 'UNDER_REVIEW', version = o.version + 1, updated_at = now() FROM legacy l WHERE o.id = l.id RETURNING o.id
)
INSERT INTO src_decisions (opportunity_id, actor_hash, action, from_status, to_status, reason, detail)
SELECT l.id, 'system:migration', 'LEGACY_STATE_CORRECTED', l.status, 'UNDER_REVIEW',
       'A customer demand gap is not a product and cannot be in a product-only state; it awaits product discovery.', '{"phase":"5-overhaul"}'::jsonb
  FROM legacy l JOIN moved m ON m.id = l.id;