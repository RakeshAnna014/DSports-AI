# Catalog module (`dsports-catalog`)

Sports, categories, brands, products, images and product attributes; the public read side used by the storefront and the AI service.

| | |
|---|---|
| API | public `GET /api/catalog/**`; admin `/api/admin/catalog/**` (ADMIN) |
| Controllers | PublicCatalogController, AdminCatalogController, ProductAttributeController |
| Tables | `sports`, `categories`, `brands`, `products`, `product_images`, `product_attributes` (V7, V11, V12, V39; seed data V8-V10, V15, V41, V43) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 17 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Public endpoints return only ACTIVE products; archiving is a state change, not a delete.
* Seed data is demonstration data (cricket first; V43 adds other sports).

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
