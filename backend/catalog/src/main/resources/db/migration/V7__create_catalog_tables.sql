CREATE TABLE sports (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(150) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_sports_name ON sports(name);
CREATE UNIQUE INDEX uq_sports_slug ON sports(slug);
CREATE INDEX ix_sports_status ON sports(status);

CREATE TABLE categories (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(150) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_categories_name ON categories(name);
CREATE UNIQUE INDEX uq_categories_slug ON categories(slug);
CREATE INDEX ix_categories_status ON categories(status);

CREATE TABLE brands (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(150) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_brands_name ON brands(name);
CREATE UNIQUE INDEX uq_brands_slug ON brands(slug);
CREATE INDEX ix_brands_status ON brands(status);
