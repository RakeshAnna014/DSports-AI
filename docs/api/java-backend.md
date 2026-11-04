# Java commerce backend REST API (Spring WebFlux, port 8080)

Exported from the running backend's /api-docs (springdoc), which is disabled in the prod profile.

Generated from the service's own OpenAPI document by `tools/docs/openapi_to_md.py` - 159 operations. Role rules are enforced in code (see the authorization matrix); the `Auth` column shows only whether the OpenAPI document declares a bearer requirement.

## Actuator

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/actuator` | Actuator root web endpoint | bearer | 200 |
| GET | `/actuator/health` | Actuator web endpoint 'health' | bearer | 200 |
| GET | `/actuator/health/{path}` | Actuator web endpoint 'health-path' | bearer | 200,404 |
| GET | `/actuator/info` | Actuator web endpoint 'info' | bearer | 200 |
| GET | `/actuator/metrics` | Actuator web endpoint 'metrics' | bearer | 200 |
| GET | `/actuator/metrics/{requiredMetricName}` | Actuator web endpoint 'metrics-requiredMetricName' | bearer | 200,404 |

## Addresses

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/customers/me/addresses` | List addresses | bearer | 200,401 |
| POST | `/api/customers/me/addresses` | Create address | bearer | 201,400,401 |
| DELETE | `/api/customers/me/addresses/{addressId}` | Delete address | bearer | 204,401,404 |
| PUT | `/api/customers/me/addresses/{addressId}` | Update address | bearer | 200,400,401,404 |
| PUT | `/api/customers/me/addresses/{addressId}/default` | Set default address | bearer | 200,401,404 |

## Admin

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/admin/catalog/brands` | List all brands | bearer | 200 |
| POST | `/api/admin/catalog/brands` | Create brand | bearer | 201,400,409 |
| GET | `/api/admin/catalog/brands/{id}` | Get brand by ID | bearer | 200,404 |
| PUT | `/api/admin/catalog/brands/{id}` | Update brand | bearer | 200,404 |
| POST | `/api/admin/catalog/brands/{id}/archive` | Archive brand | bearer | 200,404 |
| GET | `/api/admin/catalog/categories` | List all categories | bearer | 200 |
| POST | `/api/admin/catalog/categories` | Create category | bearer | 201,400,409 |
| GET | `/api/admin/catalog/categories/{id}` | Get category by ID | bearer | 200,404 |
| PUT | `/api/admin/catalog/categories/{id}` | Update category | bearer | 200,404 |
| POST | `/api/admin/catalog/categories/{id}/archive` | Archive category | bearer | 200,404 |
| GET | `/api/admin/catalog/products` | List all products | bearer | 200 |
| POST | `/api/admin/catalog/products` | Create product | bearer | 201,400,409 |
| GET | `/api/admin/catalog/products/{id}` | Get product by ID | bearer | 200,404 |
| PUT | `/api/admin/catalog/products/{id}` | Update product | bearer | 200,400,404 |
| PATCH | `/api/admin/catalog/products/{id}/archive` | Archive product | bearer | 200,404 |
| POST | `/api/admin/catalog/products/{id}/images` | Add product image | bearer | 201,400,404 |
| DELETE | `/api/admin/catalog/products/{id}/images/{imageId}` | Remove product image | bearer | 200,404 |
| PUT | `/api/admin/catalog/products/{id}/images/{imageId}/primary` | Set primary image | bearer | 200,404 |
| GET | `/api/admin/catalog/sports` | List all sports | bearer | 200 |
| POST | `/api/admin/catalog/sports` | Create sport | bearer | 201,400,409 |
| GET | `/api/admin/catalog/sports/{id}` | Get sport by ID | bearer | 200,404 |
| PUT | `/api/admin/catalog/sports/{id}` | Update sport | bearer | 200,404 |
| POST | `/api/admin/catalog/sports/{id}/archive` | Archive sport | bearer | 200,404 |
| POST | `/api/admin/inventory` | Create inventory record | bearer | 201,400,409 |
| PATCH | `/api/admin/inventory/adjust` | Adjust inventory | bearer | 200,400,404 |
| PATCH | `/api/admin/inventory/release` | Release reservation | bearer | 200,400,404 |
| PATCH | `/api/admin/inventory/reorder-level` | Update reorder level | bearer | 200,400,404 |
| PATCH | `/api/admin/inventory/reserve` | Reserve inventory | bearer | 200,400,404 |
| PATCH | `/api/admin/inventory/stock-in` | Stock in | bearer | 200,400,404 |
| PATCH | `/api/admin/inventory/stock-out` | Stock out | bearer | 200,400,404 |
| GET | `/api/admin/inventory/{id}` | Get inventory by ID | bearer | 200,404 |
| POST | `/api/admin/prices` | Create price | bearer | 201,400,409 |
| GET | `/api/admin/prices/{id}` | Get price by ID | bearer | 200,404 |
| PUT | `/api/admin/prices/{id}` | Update price | bearer | 200,400,404 |
| POST | `/api/admin/prices/{id}/activate` | Activate price | bearer | 200,404,409 |
| POST | `/api/admin/prices/{id}/archive` | Archive price | bearer | 200,404,409 |
| POST | `/api/admin/prices/{id}/schedule` | Schedule price | bearer | 200,400,404 |
| GET | `/api/v1/admin/orders/{orderId}` | Get order details (Admin) | bearer | 200,401,403,404 |
| PUT | `/api/v1/admin/orders/{orderId}/status` | Update order status (Admin) | bearer | 200,400,401,403,404,409 |
| GET | `/api/v1/admin/sales/lines` | Sold order lines for sales planning (Admin) | bearer | 200 |

## Admin Outbox

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/admin/outbox` | List outbox messages by status (default FAILED), newest first, at most 100 | bearer | 200 |
| POST | `/api/v1/admin/outbox/{id}/retry` | Re-queue a FAILED message for immediate delivery | bearer | 200 |

## Admin Refunds

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/admin/payments/{paymentId}/refunds` | Refunds of a payment | bearer | 200 |
| POST | `/api/v1/admin/payments/{paymentId}/refunds` | Refund a payment now | bearer | 201 |
| GET | `/api/v1/admin/refunds` | List all refunds, newest first | bearer | 200 |
| POST | `/api/v1/admin/refunds/{refundId}/approve` | Approve and process a requested refund, or retry a FAILED / unconfirmed PROCESSING one | bearer | 200 |
| POST | `/api/v1/admin/refunds/{refundId}/reject` | Reject a requested refund | bearer | 200 |

## Admin Returns

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/admin/returns` | All return requests | bearer | 200 |
| POST | `/api/v1/admin/returns/{id}/approve` | Approve a requested return | bearer | 200 |
| POST | `/api/v1/admin/returns/{id}/reject` | Reject a requested return | bearer | 200 |

## Authentication

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/auth/login` | Authenticate user | bearer | 200,401 |
| POST | `/api/auth/logout` | Logout | bearer | 204,401 |
| POST | `/api/auth/refresh` | Refresh access token | bearer | 200,401 |
| POST | `/api/auth/register` | Register new user | bearer | 201,409 |

## Catalog

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/catalog/brands` | List brands | bearer | 200 |
| GET | `/api/catalog/categories` | List categories | bearer | 200 |
| GET | `/api/catalog/products` | Search products | bearer | 200 |
| GET | `/api/catalog/products/{id}` | Get product | bearer | 200,404 |
| GET | `/api/catalog/sports` | List sports | bearer | 200 |

## Checkout

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/v1/checkout` | Create a new checkout | bearer | 201,400,401,404 |
| GET | `/api/v1/checkout/active` | Get active checkout | bearer | 200,401,404 |
| GET | `/api/v1/checkout/{checkoutId}` | Get checkout details | bearer | 200,401,404 |
| POST | `/api/v1/checkout/{checkoutId}/address` | Select shipping address | bearer | 200,400,401,404 |
| POST | `/api/v1/checkout/{checkoutId}/cancel` | Cancel checkout | bearer | 200,401,404 |
| POST | `/api/v1/checkout/{checkoutId}/delivery` | Select delivery method | bearer | 200,400,401,404 |
| POST | `/api/v1/checkout/{checkoutId}/validate` | Validate checkout | bearer | 200,400,401,404 |

## Checkout promotions

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| DELETE | `/api/v1/checkout/{checkoutId}/promotion` | Remove the applied promotion | bearer | 200 |
| POST | `/api/v1/checkout/{checkoutId}/promotion` | Apply a promotion code | bearer | 200,400,404,409 |
| GET | `/api/v1/checkout/{checkoutId}/promotions` | Offers for this checkout | bearer | 200,401,403,404 |

## Customer Profile

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/customers/me` | Get customer profile | bearer | 200,401 |
| PUT | `/api/customers/me` | Update customer profile | bearer | 200,400,401 |

## Fulfilment console

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/admin/fulfilment/orders` | Orders and shipments for the fulfilment console (ADMIN) | bearer | 200 |

## Inventory

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/inventory` | Stock available for every stocked product, or for up to 50 products (?productIds=a,b,c) | bearer | 200 |
| GET | `/api/inventory/{productId}` | Stock available for a product (empty list when the product is not stocked) | bearer | 200 |

## Notifications

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/notifications` | Get notification history | bearer | 200,401 |
| GET | `/api/v1/notifications/{id}` | Get notification by ID | bearer | 200,401,404 |
| POST | `/api/v1/notifications/{id}/retry` | Retry a failed notification | bearer | 200,401,403,404,409 |

## Order Insights

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/insights/co-purchases` | Products bought together with a product | bearer | 200 |

## Orders

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/orders` | Get all orders for the authenticated user | bearer | 200,401 |
| POST | `/api/v1/orders` | Place a new order | bearer | 201,400,401,404,409 |
| GET | `/api/v1/orders/{orderId}` | Get order details | bearer | 200,401,403,404 |
| PUT | `/api/v1/orders/{orderId}/cancel` | Cancel an order | bearer | 200,400,401,403,404,409 |

## Payments

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/v1/payments` | Authorize a payment for an order | bearer | 201,400,401,403,404,409 |
| GET | `/api/v1/payments/history` | Payment history of the authenticated user | bearer | 200,401 |
| GET | `/api/v1/payments/{paymentId}` | Get payment details | bearer | 200,401,403,404 |
| POST | `/api/v1/payments/{paymentId}/cancel` | Cancel an uncaptured payment | bearer | 200,401,403,404,409 |
| POST | `/api/v1/payments/{paymentId}/capture` | Capture an authorized payment | bearer | 200,401,403,404,409,502,504 |
| POST | `/api/v1/payments/{paymentId}/refund-requests` | Request a refund | bearer | 201,400,401,403,404,409 |
| GET | `/api/v1/payments/{paymentId}/refunds` | Refunds of one of the caller's payments | bearer | 200 |

## Pricing

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/prices` | List prices | bearer | 200 |
| GET | `/api/prices/{id}` | Get price by ID | bearer | 200,404 |

## Product Attributes

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| PUT | `/api/admin/catalog/products/{id}/attributes` | Replace the attributes of a product (admin) | bearer | 200 |
| GET | `/api/catalog/product-attributes` | Structured facts of every product, keyed by product id (one request for filters and listings) | bearer | 200 |
| GET | `/api/catalog/products/{id}/attributes` | Structured facts of a product (skill level, material, surface, ...) | bearer | 200 |

## Returns

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/orders/{orderId}/returns/eligibility` | Return eligibility of a product in one of my orders | bearer | 200 |
| GET | `/api/v1/returns` | My return requests | bearer | 200 |
| POST | `/api/v1/returns` | Request a return | bearer | 201 |
| GET | `/api/v1/returns/{id}` | One of my return requests | bearer | 200 |

## Reviews

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/v1/reviews` | Create a review | bearer | 201,400,401,409 |
| POST | `/api/v1/reviews/admin/{id}/moderate` | Moderate a review | bearer | 200,403,404 |
| GET | `/api/v1/reviews/eligibility` | Can the current customer review this product? | bearer | 200 |
| GET | `/api/v1/reviews/product/{productId}` | Get product reviews | bearer | 200,404 |
| GET | `/api/v1/reviews/product/{productId}/summary` | Get rating summary | bearer | 200 |
| GET | `/api/v1/reviews/{id}` | Get review by ID | bearer | 200,401,404 |
| PUT | `/api/v1/reviews/{id}` | Update a review | bearer | 200,401,403,404 |
| POST | `/api/v1/reviews/{id}/helpful` | Mark review as helpful | bearer | 200,401,404,409 |

## Shipments

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/v1/shipments` | Create the shipment for a paid order (admin) | bearer | 200,400,403,404 |
| PUT | `/api/v1/shipments/admin/{id}/status` | Update shipment status | bearer | 200,403,404 |
| GET | `/api/v1/shipments/by-order/{orderId}` | Shipment and tracking history of one of the caller's orders | bearer | 200,403,404 |
| GET | `/api/v1/shipments/track/{trackingNumber}` | Track shipment by tracking number | bearer | 200,404 |
| GET | `/api/v1/shipments/{id}` | Get shipment by ID | bearer | 200,401,404 |
| POST | `/api/v1/shipments/{id}/courier` | Assign courier to shipment | bearer | 200,403,404 |

## Shopper Profiles

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/api/customers/me/family-profiles` | Add a family profile | bearer | 201 |
| DELETE | `/api/customers/me/family-profiles/{id}` | Delete a family profile and all preferences stored for it | bearer | 204 |
| PUT | `/api/customers/me/family-profiles/{id}` | Rename / change the age band of a family profile | bearer | 200 |
| GET | `/api/customers/me/profiles` | The account's profiles (own + family) and which one is active | bearer | 200 |
| GET | `/api/customers/me/profiles/active` | The active profile with its preferences | bearer | 200 |
| PUT | `/api/customers/me/profiles/active` | Switch the active profile | bearer | 200 |
| GET | `/api/customers/me/profiles/{id}` | One profile with its preferences | bearer | 200 |
| DELETE | `/api/customers/me/profiles/{id}/preferences` | Reset a profile's preferences | bearer | 200 |
| PUT | `/api/customers/me/profiles/{id}/preferences` | Replace a profile's preferences | bearer | 200 |

## Shopping Cart

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| DELETE | `/api/v1/cart` | Clear cart | bearer | 200,401,404 |
| GET | `/api/v1/cart` | Get current user's active cart | bearer | 200,401,404 |
| POST | `/api/v1/cart/items` | Add item to cart | bearer | 200,400,401,404,409 |
| DELETE | `/api/v1/cart/items/{itemId}` | Remove item from cart | bearer | 200,401,404 |
| PUT | `/api/v1/cart/items/{itemId}` | Update cart item quantity | bearer | 200,400,401,404,409 |

## Support Cases

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/support/cases` | My support cases | bearer | 200 |
| POST | `/api/v1/support/cases` | Open a support case | bearer | 200 |
| GET | `/api/v1/support/cases/{ref}` | One of my support cases | bearer | 200 |
| POST | `/api/v1/support/cases/{ref}/escalate` | Ask for a human to handle my case | bearer | 200 |
| POST | `/api/v1/support/cases/{ref}/messages` | Add a message to my case | bearer | 200 |
| POST | `/api/v1/support/cases/{ref}/reopen` | Reopen my resolved case | bearer | 200 |

## Support Staff

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/api/v1/support/staff/analytics` | Support analytics derived from the persisted cases | bearer | 200 |
| GET | `/api/v1/support/staff/cases` | Search support cases | bearer | 200 |
| GET | `/api/v1/support/staff/cases/{ref}` | A case with internal notes, evidence, priority reasons and the full history | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/assign` | Take a case and/or set its queue | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/notes` | Add an internal note (never visible to the customer) | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/priority` | Change the priority | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/replies` | Reply to the customer (visible on their case) | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/resolve` | Resolve a case with an outcome and a note | bearer | 200 |
| POST | `/api/v1/support/staff/cases/{ref}/status` | Change the status | bearer | 200 |
| GET | `/api/v1/support/staff/report` | Ticket report for a period | bearer | 200 |

## Wishlist

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| DELETE | `/api/v1/wishlist` | Clear wishlist | bearer | 200,401,404 |
| GET | `/api/v1/wishlist` | Get current user's wishlist | bearer | 200,401,404 |
| GET | `/api/v1/wishlist/count` | Get wishlist item count | bearer | 200,401,404 |
| POST | `/api/v1/wishlist/items` | Add item to wishlist | bearer | 200,400,401,404,409 |
| DELETE | `/api/v1/wishlist/items/{itemId}` | Remove item from wishlist | bearer | 200,401,404 |
| POST | `/api/v1/wishlist/items/{itemId}/move-to-cart` | Move wishlist item to cart | bearer | 200,401,404 |

