# PR #20 – Shipment & Delivery Management

## Architecture

Follows Clean Architecture / DDD with four layers:

```
presentation (interfaces)
    ↓
application (use cases, ports)
    ↓
domain (aggregates, value objects, enums, events)
    ↓
infrastructure (persistence, gateways, event adapters)
```

## Shipment Lifecycle

```
CREATED → READY_FOR_PICKUP → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
   │                                                  │
   └── FAILED ←───────────────────────────────────────┘
   │
   └── RETURNED (from PICKED_UP, IN_TRANSIT)
```

- **CREATED**: Shipment record created when order is confirmed
- **READY_FOR_PICKUP**: Courier assigned, tracking number generated
- **PICKED_UP**: Package picked up by courier
- **IN_TRANSIT**: Package in transit to destination
- **OUT_FOR_DELIVERY**: Package out for final delivery
- **DELIVERED**: Package delivered (terminal state)
- **FAILED**: Delivery failed (can retry from FAILED → READY_FOR_PICKUP)
- **RETURNED**: Package returned to sender (terminal state)

## Domain Model

### Aggregate Root

```
Shipment {
    id: ShipmentId
    shipmentNumber: ShipmentNumber
    trackingNumber: TrackingNumber (nullable)
    orderId: UUID
    userId: UUID
    courierProvider: CourierProvider (nullable)
    status: ShipmentStatus
    shippingAddress: ShippingAddress
    estimatedDeliveryDate: Instant (nullable)
    actualDeliveryDate: Instant (nullable)
    shippedAt: Instant (nullable)
    deliveredAt: Instant (nullable)
    createdAt: Instant
    updatedAt: Instant
    version: int
    domainEvents: List<DomainEvent> (transient)
}
```

### Value Objects

- `ShipmentId` – UUID wrapper
- `ShipmentNumber` – auto-generated "SH-" + UUID
- `TrackingNumber` – auto-generated "TRK-" + UUID
- `ShippingAddress` – street, city, state, zipCode, country

### Domain Events

- `ShipmentCreatedEvent`
- `ShipmentPickedUpEvent`
- `ShipmentInTransitEvent`
- `ShipmentOutForDeliveryEvent`
- `ShipmentDeliveredEvent`
- `ShipmentReturnedEvent`

## Courier Abstraction

```
CourierGateway (interface)
    ↑
MockCourierGateway (implementation)
```

### CourierGateway Methods

- `createShipment(Shipment)` → `CourierGatewayResult`
- `trackShipment(TrackingNumber)` → `CourierGatewayResult`
- `cancelShipment(TrackingNumber)` → `CourierGatewayResult`
- `updateStatus(TrackingNumber, ShipmentStatus)` → `CourierGatewayResult`

### Future Courier Integrations

| Provider | Integration Type |
|----------|-----------------|
| Delhivery | REST API |
| BlueDart | REST API |
| DTDC | REST API |
| FedEx | REST API |
| DHL | REST API |

## Tracking Flow

1. Shipment created → auto-assigns mock courier + tracking number
2. Admin updates status via `PUT /api/v1/admin/shipments/{id}/status`
3. Each status change creates an immutable `ShipmentTracking` entry
4. Users track via `GET /api/v1/shipments/track/{trackingNumber}`

## Database Changes

### Table: `shipments`

| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PK |
| shipment_number | VARCHAR(20) | NOT NULL |
| tracking_number | VARCHAR(20) | UNIQUE (nullable) |
| order_id | UUID | NOT NULL, indexed |
| user_id | UUID | NOT NULL, indexed |
| courier_provider | VARCHAR(20) | CHECK (MOCK) |
| status | VARCHAR(20) | NOT NULL, CHECK, indexed |
| street/city/state/zip_code/country | VARCHAR | NOT NULL |
| estimated_delivery_date | TIMESTAMPTZ | |
| actual_delivery_date | TIMESTAMPTZ | |
| shipped_at | TIMESTAMPTZ | |
| delivered_at | TIMESTAMPTZ | |
| created_at/updated_at | TIMESTAMPTZ | NOT NULL |
| version | INT | NOT NULL, @Version |

### Table: `shipment_tracking`

| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PK |
| shipment_id | UUID | FK → shipments(id), indexed |
| status | VARCHAR(20) | NOT NULL, CHECK |
| location | VARCHAR(255) | |
| description | TEXT | NOT NULL |
| event_time | TIMESTAMPTZ | NOT NULL |

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/shipments` | User | Create shipment for confirmed order |
| GET | `/api/v1/shipments/{id}` | User | Get shipment by ID (own only) |
| GET | `/api/v1/shipments/track/{trackingNumber}` | Public | Track by tracking number |
| POST | `/api/v1/shipments/{id}/courier` | Admin | Assign courier to shipment |
| PUT | `/api/v1/admin/shipments/{id}/status` | Admin | Update shipment status |

## Business Rules

1. Only CONFIRMED orders can be shipped
2. Each order has exactly one shipment
3. Tracking numbers must be unique
4. Shipment history (tracking entries) is immutable
5. Delivered/Returned shipments cannot transition to previous states
6. Optimistic locking via `@Version` – returns HTTP 409 on conflict

## Auto-Shipment on Order Confirmed

`OrderConfirmedEventListener` listens for `OrderConfirmedEvent` and auto-creates a shipment with default address ("N/A") via `CreateShipmentUseCase`. In production, the actual shipping address from the order would be used.

## Files Created/Modified

### New Files

**Domain Layer (16 files)**
- `Shipment.java`, `ShipmentId.java`, `ShipmentNumber.java`, `TrackingNumber.java`, `ShippingAddress.java`
- `ShipmentStatus.java`, `CourierProvider.java`, `ShipmentTracking.java`
- `ShipmentCreatedEvent.java`, `ShipmentPickedUpEvent.java`, `ShipmentInTransitEvent.java`
- `ShipmentOutForDeliveryEvent.java`, `ShipmentDeliveredEvent.java`, `ShipmentReturnedEvent.java`
- `ShipmentDomainException.java`, `ShipmentErrorCode.java`

**Application Layer (17 files)**
- `ShipmentRepository.java`, `CourierGateway.java`, `EventPublisher.java`, `OrderQueryService.java`
- `CreateShipmentCommand.java`, `AssignCourierCommand.java`, `UpdateShipmentStatusCommand.java`
- `GetShipmentQuery.java`, `TrackShipmentQuery.java`, `ShipmentHistoryQuery.java`
- `ShipmentResult.java`, `ShipmentTrackingResult.java`
- `CreateShipmentUseCase.java`, `AssignCourierUseCase.java`, `TrackShipmentUseCase.java`
- `UpdateShipmentStatusUseCase.java`, `GetShipmentUseCase.java`, `ShipmentResultMapper.java`

**Infrastructure Layer (11 files)**
- `ShipmentEntity.java`, `ShipmentTrackingEntity.java`
- `SpringR2dbcShipmentRepository.java`, `SpringR2dbcShipmentTrackingRepository.java`
- `ShipmentR2dbcRepositoryAdapter.java`, `ShipmentEntityMapper.java`
- `MockCourierGateway.java`, `ShipmentSpringEventPublisherAdapter.java`
- `OrderConfirmedEventListener.java`, `ShipmentInfrastructureConfiguration.java`
- `V27__create_shipment_tables.sql`

**Presentation Layer (1 file)**
- `ShipmentController.java`

**Tests (7 files, 28 tests)**
- `ShipmentTest.java` (10 tests)
- `CreateShipmentUseCaseTest.java` (4 tests)
- `AssignCourierUseCaseTest.java` (3 tests)
- `TrackShipmentUseCaseTest.java` (2 tests)
- `UpdateShipmentStatusUseCaseTest.java` (2 tests)
- `MockCourierGatewayTest.java` (2 tests)
- `ShipmentControllerTest.java` (5 tests)

**Frontend (3 files)**
- `src/api/shipments.ts`
- `src/features/shipments/ShipmentPage.tsx`
- `src/features/shipments/TrackingPage.tsx`

### Modified Files

- `backend/pom.xml` – Added `shipment` module + dependency management
- `backend/bootstrap/pom.xml` – Added `dsports-shipment` dependency
- `backend/bootstrap/.../OpenApiConfig.java` – Added Shipments tag + GroupedOpenApi
- `backend/bootstrap/.../GlobalExceptionHandler.java` – Added ShipmentDomainException handler
- `frontend/src/routes/index.tsx` – Added shipment routes
- `frontend/src/layouts/MainLayout.tsx` – Added Track nav + menu item

## Testing Summary

- **Unit Tests**: 28 tests across 7 test classes
- **Coverage**: Domain aggregate, all 4 use cases, controller endpoints, mock courier gateway
- **Pattern**: JUnit 5 + Reactor StepVerifier + Mockito
- **Test Status**: All 28 tests pass

## Production Readiness

- [x] Build succeeds (`mvn compile -pl bootstrap -am`)
- [x] All tests pass (`mvn test -pl shipment`)
- [x] Flyway migration (V20) with constraints and indexes
- [x] Swagger documentation (shipments API group)
- [x] Global exception handling with proper HTTP status codes
- [x] Optimistic locking with @Version (HTTP 409 on conflict)
- [x] Reactive non-blocking (WebFlux + R2DBC)
- [x] Security (user ownership check, admin-only endpoints)
- [x] Constructor injection (no field injection)
- [x] Clean Architecture (no layer violations)
- [x] Immutable tracking history
- [x] Frontend pages with tracking timeline and shipment details
