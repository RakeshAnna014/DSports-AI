# 15. Multi-sport demo catalogue, product images and demo customers

The customer experience was extended from a cricket-only catalogue to **11 sports and 134 products with real product images**, so recommendations, search, comparison,
bundles and personalisation can be shown across sports. This is DEMO DATA; nothing here is production data.

## What was added

| Area | Result |
|---|---|
| Sports | Cricket, Football, Badminton, Basketball, Tennis, Volleyball, Running, Fitness, Swimming, Cycling, Table Tennis |
| Products | **134** (the original 50 kept: ids 1001-1050; 84 new: 1051-1134); 8 to 19 per sport; real INR prices with MRP and discount; stock; 3 images each |
| Source of truth | one Python spec, `tools/catalog/spec.py` (taxonomy, products, kit roles, cross-sport relations), rendered by `tools/catalog/generate.py` |
| Migration | `backend/bootstrap/.../db/migration/V43__multisport_demo_catalog.sql` (generated; `ON CONFLICT DO NOTHING` for new rows, updates the old placeholder image rows) |
| Images | **402 original SVG illustrations** in `frontend/public/products/<sport>/<slug>-<1..3>.svg` (front / angle / detail) plus 11 sport banners in `public/sports/`; served by Vite, referenced from `product_images` |
| API | the product list now carries `imageUrl` and `images`; bulk `GET /api/catalog/product-attributes` (the page cap stays 100, so clients page through the catalogue) |
| Kits | per-sport `kits.json` (roles such as bat, gloves, helmet) drives "Complete your setup", budget bundles ("build a home gym under INR 10,000") and recommendations |
| Cross-sport | explicit relations with an explanation ("a gym bag also carries running kit"); a different sport is only ever recommended with a reason |
| Personalisation | uses only the signed-in customer's own orders (read with their token); multi-sport histories get recommendations from several sports |
| Frontend | `ProductImage` (lazy, skeleton, contain-fit, DSports fallback visual, never a broken icon), image about half the card, gallery with thumbnails on the detail page, sports tiles with artwork, filters (sport, category, brand, price, availability, skill level) |
| Validation | `npm run validate:catalog` (spec vs files) and `npm run validate:catalog:live` (also checks the running API: every product has a real image file, sport, category, positive price, list price not below selling price) |

## Honest limits

- The images are **original vector illustrations** generated from the spec, not photographs: no licensed photography was available and no unverified URLs were used.
  They are consistent, local and verified to exist, which is what the validation checks; they are not studio product photos.
- Prices, MRPs, stock, ratings (none seeded) and descriptions are invented for the demo.
- No review/rating data exists for the new products, so catalogue grids show no ratings and there is no rating filter.
- Image upload / condition inspection, exchange flows, store credit and price-drop alerts are **not** implemented (no backend support); the assistant says so rather than faking them.

## Demo customers (`e2e/ai/seed_demo_customers.py`, DEMO DATA ONLY)

Created through the real APIs (register, cart, checkout, order, payment, shipment transitions), idempotent. Password `Passw0rd!x` unless `--password` is given.

| Customer | History | State |
|---|---|---|
| `demo.cricket@example.com` | MRF Cricket Bat - Virgin, MRF Cricket Gloves - Pro | delivered |
| `demo.running@example.com` | Asics Running Shoes - Daily Trainer, Puma Running Socks - Pack of 3 | out for delivery |
| `demo.football@example.com` | Nivia Football Boots - Dominator, Nivia Football Shin Guards - Ranger | delivered |
| `demo.multi@example.com` | SS bat, Adidas running shoes, Nivia gym gloves, Nike football (delivered); Yonex racquet (processing) | mixed |

## Tests

`e2e/ai-multisport.mjs` (14 steps, real Chrome): real images on every card with the image about half the card height, trending across 5+ sports, 11 sport tiles with artwork,
every sport opens a filtered catalogue with real products, filters, natural-language search in all 11 sports with real images, comparison, a home-gym bundle within budget with savings
and "Add all", a cross-sport cart, the broken-image fallback, the detail page gallery/specs, mobile (390 px), and the cricket / running / multi-sport demo customers with real timelines
and an interactive return selection. Run by `bash e2e/run-ai-ui.sh` together with the earlier journeys.

Regenerate everything: `python tools/catalog/generate.py` (deterministic).
