# Service guides

One guide per service or area. Each answers the same eleven questions: business purpose, entry points, request lifecycle, business rules, data ownership, external dependencies, security and authorization, failure handling and observability, how to run and test, what is implemented / partial / missing, and which files to read first.

| Guide | Runtime | Owns | Port |
|---|---|---|---|
| [frontend.md](frontend.md) | React + Vite | nothing persistent; tokens in the browser | 3000 |
| [java-backend.md](java-backend.md) | Java 21 / Spring WebFlux | database `dsports`: identity, catalogue, price, stock, cart, orders, payments, refunds, shipments, returns, reviews, wishlist, notifications, support cases, promotions | 8080 |
| [ai-platform.md](ai-platform.md) | Python 3.13 / FastAPI | shared foundation of the AI service: auth, tool registry, guard, model layer, observability; database `dsports_ai` | 8090 |
| [customer-ai.md](customer-ai.md) | AI service | assistant, recommendations, RAG, MCP | 8090 |
| [support.md](support.md) | Java + AI service | support cases (Java), investigation and staff assistance (AI) | 8080 / 8090 |
| [inventory-ai.md](inventory-ai.md) | AI service | `inv_*` mart, forecasts, stock risk, replenishment | 8090 |
| [engineering-ai.md](engineering-ai.md) | AI service | `eng_*` log investigation | 8090 |
| [sourcing.md](sourcing.md) | AI service | `src_*` product and supplier intelligence | 8090 |
| [planning.md](planning.md) | AI service + Java sales feed | `pln_*` sales and inventory planning | 8090 |

Related: [../ARCHITECTURE-OVERVIEW.md](../ARCHITECTURE-OVERVIEW.md) (diagrams), [../DATABASE.md](../DATABASE.md), [../api/README.md](../api/README.md), [../STATUS.md](../STATUS.md), [../learning-guide/](../learning-guide/00-master-learning-guide.md) (study order).

Conventions used in these guides: **synthetic**, **demonstration** and **POC** mean data generated for the project and labelled as such; **live** means real records created in this system; "observed" means seen in a recorded test run, not inferred from reading code.
