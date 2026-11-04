# Bootstrap module (`dsports-bootstrap`): the runnable application

The only module that produces the executable jar (`bootstrap/target/dsports-bootstrap-1.0.0-SNAPSHOT.jar`). Every other module is a library that this one wires together.

| Concern | Where |
|---|---|
| Application class | `com.dsports.DSportsApplication` |
| Module wiring (use cases as beans) | `config/*ModuleConfig.java` (cart, checkout, order, payment, shipment, returns, support, wishlist, profiles) |
| Security chain and route rules | `security/SecurityConfig.java`, `security/JwtWebFilter.java`, `security/ratelimit/*` |
| Error contract | `exception/GlobalExceptionHandler.java`, `ApiErrors.java` (uniform `ApiError` body, no stack traces) |
| Correlation id, OpenAPI, metrics | `config/CorrelationIdFilter.java`, `OpenApiConfig.java`, `ObservabilityConfig.java`, `observability/BusinessMetricsListener.java` |
| Transactional outbox | `outbox/R2dbcOutboxWriter.java`, `OutboxDispatcher.java`, `OutboxHandlers.java`, `AdminOutboxController.java` |
| Scheduled jobs | `jobs/ExpireUnpaidOrdersJob.java` |
| Admin fulfilment board | `fulfilment/FulfilmentBoardController.java` |
| Configuration | `src/main/resources/application.yml`, `application-local.yml` (development-only defaults), `application-prod.yml` (no defaults; docs disabled) |
| Migrations | `src/main/resources/db/migration` holds V33 and V43; the other versions live in their module (one shared sequence, V1 to V47) |

## Run and test
Stop a running backend before rebuilding (Windows locks the jar): `cd backend && mvn -B -o clean install -DskipTests`, then `bash e2e/ai/start-backend.sh`. Tests: `mvn -B -o package` (unit), `mvn -B -o test -Pintegration-test -Dapi.version=1.44` (Testcontainers). Security policy tests: `SecurityPolicyTest` (33).

More: [docs/services/java-backend.md](../../docs/services/java-backend.md), [docs/OPERATIONS.md](../../docs/OPERATIONS.md), [docs/SECURITY.md](../../docs/SECURITY.md).
