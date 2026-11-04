CREATE TABLE IF NOT EXISTS wishlists (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     INTEGER     NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wishlist_items (
    id            UUID        PRIMARY KEY,
    wishlist_id   UUID        NOT NULL REFERENCES wishlists(id) ON DELETE CASCADE,
    product_id    UUID        NOT NULL,
    product_name  VARCHAR(255) NOT NULL,
    product_image VARCHAR(512),
    current_price NUMERIC(10,2),
    added_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wishlists_user_id ON wishlists(user_id);

CREATE INDEX IF NOT EXISTS idx_wishlist_items_wishlist_id ON wishlist_items(wishlist_id);

CREATE INDEX IF NOT EXISTS idx_wishlist_items_product_id ON wishlist_items(product_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wishlist_items_unique_product ON wishlist_items(wishlist_id, product_id);
