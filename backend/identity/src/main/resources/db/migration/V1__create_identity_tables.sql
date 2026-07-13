CREATE TABLE customers (
    id                  UUID         PRIMARY KEY,
    email               VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(256),
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    phone               VARCHAR(16),
    status              VARCHAR(20)  NOT NULL,
    failed_login_attempts INT        NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP,
    last_login_at       TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    deleted_at          TIMESTAMP
);

CREATE TABLE customer_roles (
    customer_id UUID        NOT NULL REFERENCES customers(id),
    role        VARCHAR(20) NOT NULL,
    PRIMARY KEY (customer_id, role)
);

CREATE TABLE customer_auth_providers (
    customer_id UUID        NOT NULL REFERENCES customers(id),
    provider    VARCHAR(20) NOT NULL,
    PRIMARY KEY (customer_id, provider)
);

CREATE UNIQUE INDEX uq_customers_email ON customers(email);

CREATE INDEX ix_customer_roles_customer_id ON customer_roles(customer_id);
CREATE INDEX ix_customer_auth_providers_customer_id ON customer_auth_providers(customer_id);
