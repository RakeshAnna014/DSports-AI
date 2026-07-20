# PR #12 — Product Management

## Design Decisions

### Why Product is an Aggregate Root

Product owns its lifecycle independently of Brand, Category, or Sport. It has its own:
- Identity (`ProductId`)
- Invariants (SKU/slug uniqueness, max 20 images, archived state)
- Behaviors (`create`, `update`, `archive`, `addImage`, `removeImage`, `changePrimaryImage`)
- Domain events

ProductImage is an **entity within the Product aggregate**. It has local identity (`ProductImageId`) but no meaning outside the Product. The aggregate enforces the 20-image limit and ensures exactly one primary image.

### Why Inventory is Excluded

Inventory (stock levels, warehouse locations) belongs in a separate `inventory` bounded context because:
- Different lifecycle: Products are created once; inventory fluctuates daily
- Different consistency requirements: Inventory can be eventually consistent with catalog
- Different access patterns: Inventory is read/written by order processing, not catalog browsing

Integration happens via `ProductId` references and domain events.

### Why Price is Excluded

Pricing has different rules and lifecycle:
- Prices change based on promotions, seasons, customer segments
- Virtual Cart and Order contexts need price snapshots at purchase time
- A separate `pricing` bounded context (or within `order`/`billing`) is more appropriate

### Future Inventory Integration

1. `ProductCreatedEvent` → Inventory context creates an empty stock record
2. `ProductArchivedEvent` → Inventory context stops tracking
3. Product ID is the shared reference; no direct catalog-inventory joins

### Future Pricing Integration

1. A `pricing` bounded context owns the Price value object and pricing rules
2. Product read APIs will join with pricing data at the API composition layer (BFF or GraphQL)
3. The Product aggregate stores only a `priceId` reference, not embedded price data

### Future Order Integration

1. Order context references products by `ProductId`
2. When an order is placed, the Order context snapshots product details (name, SKU, price)
3. `ProductUpdatedEvent` can trigger re-indexing in order search views

## Architecture

### Domain Layer

- **Aggregate**: `Product` — root with `ProductImage` entities
- **Value Objects**: `ProductId`, `SKU`, `ProductName`, `ProductDescription`, `Slug`, `Weight`, `Dimensions`, `ProductImageId`, `ProductImageUrl`
- **Domain Events**: `ProductCreatedEvent`, `ProductUpdatedEvent`, `ProductArchivedEvent`, `ProductImageAddedEvent`, `ProductImageRemovedEvent`, `PrimaryImageChangedEvent`
- **Enums**: `Status` (reused from catalog)

### Application Layer

- **Commands**: `CreateProductCommand`, `UpdateProductCommand`, `ArchiveProductCommand`, `AddProductImageCommand`, `RemoveProductImageCommand`, `ChangePrimaryImageCommand`
- **Results**: `ProductResult`, `ProductSummaryResult`, `ProductImageResult`
- **Ports**: `ProductRepository`, `ProductFilter`
- **Use Cases**: `CreateProduct`, `UpdateProduct`, `ArchiveProduct`, `GetProduct`, `GetProducts`, `AddImage`, `RemoveImage`, `ChangePrimaryImage`

### Infrastructure Layer

- **Flyway**: V11 (products table), V12 (product_images table)
- **R2DBC**: `SpringR2dbcProductRepository`, `SpringR2dbcProductImageRepository`
- **Adapter**: `ProductR2dbcRepositoryAdapter`
- **Mapper**: `ProductEntityMapper`

### API Layer

- **Public** (`GET /api/catalog/products`): Filtered, paginated, sorted listing
- **Public** (`GET /api/catalog/products/{id}`): Single product detail with images
- **Admin** (`POST /api/admin/catalog/products`): Create
- **Admin** (`PUT /api/admin/catalog/products/{id}`): Update
- **Admin** (`PATCH /api/admin/catalog/products/{id}/archive`): Soft-delete
- **Admin** (`POST /api/admin/catalog/products/{id}/images`): Add image
- **Admin** (`DELETE /api/admin/catalog/products/{id}/images/{imageId}`): Remove image
- **Admin** (`PUT /api/admin/catalog/products/{id}/images/{imageId}/primary`): Change primary image

## Business Rules Enforced

| Rule | Enforcement |
|------|------------|
| SKU unique | DB unique index + application check |
| Slug unique | DB unique index + application check |
| Name required | Value object validation |
| Exactly one Brand/Category/Sport | Constructor null check |
| One primary image | `clearPrimaryFlag()` on add/change |
| Max 20 images | Guard in `addImage()` |
| Archived → no modification | `IllegalStateException` in `update()`, `addImage()`, `removeImage()`, `changePrimaryImage()` |
| Cannot archive twice | `IllegalStateException` in `archive()` |

## Self-Review

### Findings by Severity

| Severity | Count | Notes |
|----------|-------|-------|
| 🔴 BLOCKER | 0 | — |
| 🟠 HIGH | 0 | — |
| 🟡 MEDIUM | 3 | See below |
| 🟢 LOW | 2 | See below |

### 🟡 MEDIUM

1. **SKU mutability**: The aggregate allows SKU changes via `update()`. In practice, SKU should rarely change. Consider making SKU immutable after creation, or auditing SKU changes. This is a business decision, not a code bug.

2. **Image delete cascade via raw SQL**: The adapter uses `DELETE FROM product_images WHERE product_id = :productId` which bypasses R2DBC auditing. If soft-delete or audit columns are added to `product_images` later, this query needs updating.

3. **Filtered query uses string concatenation for ORDER BY**: The `sanitizeSortColumn()` method prevents SQL injection but is fragile. A future developer adding a new sort column must remember to update both the filter and the sanitizer.

### 🟢 LOW

1. **No cache layer**: Repeated queries for the same products will hit the database every time. Add Redis caching at the adapter level when read traffic justifies it.

2. **Image save is N+1**: Each image is saved individually. For bulk imports, batch inserts would be more efficient. Acceptable for admin operations (few images per product).

### Decision

PR #12 is production-ready and recommended for merge.
