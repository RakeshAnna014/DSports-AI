-- Which catalogue sports can be booked as courts or grounds (ground booking, ADR-018). Default false: a sport is shop-only until an admin says otherwise.
-- A bookable sport that has no products is hidden from the shop (see SportRepository.findAllShoppable), so it never appears there as an empty sport.
ALTER TABLE sports ADD COLUMN bookable BOOLEAN NOT NULL DEFAULT FALSE;

-- Pickleball is a court sport that the shop catalogue did not have yet. Idempotent: skipped when a sport with that name or slug exists.
INSERT INTO sports (id, name, slug, description, status, created_at, updated_at, version)
SELECT gen_random_uuid(), 'Pickleball', 'pickleball', 'Paddle sport played on a small court with a perforated ball', 'ACTIVE', NOW(), NOW(), 1
WHERE NOT EXISTS (SELECT 1 FROM sports WHERE name = 'Pickleball' OR slug = 'pickleball');

UPDATE sports SET bookable = TRUE
WHERE slug IN ('cricket', 'football', 'badminton', 'tennis', 'pickleball', 'basketball', 'volleyball', 'table-tennis');
