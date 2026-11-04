-- Customer support cases: a persisted, owner-scoped complaint workflow with an append-only history.
-- The Java domain enforces the lifecycle; the constraints below are the database's last line of defence.
CREATE SEQUENCE support_case_seq START 1;

CREATE TABLE support_cases (
    id                         UUID PRIMARY KEY,
    case_number                VARCHAR(30)   NOT NULL,
    user_id                    UUID          NOT NULL,
    category                   VARCHAR(30)   NOT NULL,
    subcategory                VARCHAR(60),
    priority                   VARCHAR(10)   NOT NULL,
    priority_reasons           TEXT          NOT NULL DEFAULT '[]',
    status                     VARCHAR(30)   NOT NULL,
    subject                    VARCHAR(200)  NOT NULL,
    description                VARCHAR(2000) NOT NULL,
    order_id                   UUID,
    order_number               VARCHAR(40),
    product_id                 UUID,
    product_name               VARCHAR(255),
    payment_id                 UUID,
    return_id                  UUID,
    refund_reference           VARCHAR(100),
    order_status_at_creation   VARCHAR(30),
    shipment_status_at_creation VARCHAR(30),
    order_total                NUMERIC(14, 2),
    currency                   VARCHAR(3),
    investigation_summary      VARCHAR(4000),
    evidence                   TEXT          NOT NULL DEFAULT '[]',
    policy_citations           TEXT          NOT NULL DEFAULT '[]',
    missing_information        TEXT          NOT NULL DEFAULT '[]',
    resolution_recommendation  VARCHAR(1000),
    resolution_outcome         VARCHAR(40),
    resolution_note            VARCHAR(1000),
    resolved_by                UUID,
    assigned_queue             VARCHAR(40),
    assigned_to                UUID,
    escalation_state           VARCHAR(12)   NOT NULL DEFAULT 'NONE',
    escalation_reason          VARCHAR(500),
    safety_related             BOOLEAN       NOT NULL DEFAULT FALSE,
    source                     VARCHAR(20)   NOT NULL DEFAULT 'WEB',
    idempotency_key            VARCHAR(100)  NOT NULL,
    reopen_count               INTEGER       NOT NULL DEFAULT 0,
    version                    INTEGER       NOT NULL DEFAULT 1,
    created_at                 TIMESTAMPTZ   NOT NULL,
    updated_at                 TIMESTAMPTZ   NOT NULL,
    resolved_at                TIMESTAMPTZ,
    CONSTRAINT uq_support_case_number UNIQUE (case_number),
    CONSTRAINT uq_support_case_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_support_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'WAITING_FOR_CUSTOMER', 'WAITING_FOR_EXTERNAL_PARTY', 'RESOLVED')),
    CONSTRAINT ck_support_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_support_escalation CHECK (escalation_state IN ('NONE', 'REQUESTED', 'ACCEPTED')),
    CONSTRAINT ck_support_category CHECK (category IN ('MISSING_DELIVERY', 'DELAYED_DELIVERY', 'DAMAGED_PRODUCT', 'WRONG_PRODUCT',
        'PARTIAL_DELIVERY', 'PAYMENT_ISSUE', 'DUPLICATE_PAYMENT', 'REFUND_ISSUE', 'RETURN_ISSUE', 'EXCHANGE_REQUEST', 'PRODUCT_COMPLAINT', 'GENERAL')),
    CONSTRAINT ck_support_resolved_has_outcome CHECK ((status = 'RESOLVED') = (resolution_outcome IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX ix_support_cases_user_updated ON support_cases (user_id, updated_at DESC);
CREATE INDEX ix_support_cases_status ON support_cases (status);
CREATE INDEX ix_support_cases_order ON support_cases (user_id, order_id);
CREATE INDEX ix_support_cases_assigned ON support_cases (assigned_to);

CREATE TABLE support_case_messages (
    id          UUID PRIMARY KEY,
    case_id     UUID          NOT NULL REFERENCES support_cases (id),
    author_type VARCHAR(10)   NOT NULL,
    author_id   UUID,
    internal    BOOLEAN       NOT NULL DEFAULT FALSE,
    body        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_support_message_author CHECK (author_type IN ('CUSTOMER', 'STAFF', 'SYSTEM')),
    CONSTRAINT ck_support_internal_is_staff CHECK (NOT internal OR author_type = 'STAFF')
);

CREATE INDEX ix_support_messages_case ON support_case_messages (case_id, created_at);

-- Append-only history. There is deliberately no UPDATE or DELETE path in the application.
CREATE TABLE support_case_events (
    id               UUID PRIMARY KEY,
    case_id          UUID          NOT NULL REFERENCES support_cases (id),
    event_type       VARCHAR(30)   NOT NULL,
    actor_type       VARCHAR(10)   NOT NULL,
    actor_id         UUID,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30),
    detail           VARCHAR(1000),
    customer_visible BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_support_events_case ON support_case_events (case_id, created_at);
