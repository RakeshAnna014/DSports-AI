-- Spring Data R2DBC treats an entity with a primitive @Version of 0 as new and INSERTs it.
-- The application always writes version >= 1, but seed scripts relied on DEFAULT 0, so every
-- seeded row could never be updated (saving it attempted a duplicate INSERT).
-- Mark existing seed rows as persisted and default future seed rows to 1.
UPDATE sports SET version = 1 WHERE version = 0;
UPDATE categories SET version = 1 WHERE version = 0;
UPDATE brands SET version = 1 WHERE version = 0;
UPDATE products SET version = 1 WHERE version = 0;

ALTER TABLE sports ALTER COLUMN version SET DEFAULT 1;
ALTER TABLE categories ALTER COLUMN version SET DEFAULT 1;
ALTER TABLE brands ALTER COLUMN version SET DEFAULT 1;
ALTER TABLE products ALTER COLUMN version SET DEFAULT 1;
