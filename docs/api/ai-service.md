# AI service REST API (Python / FastAPI, port 8090)

Exported offline with the app factory (no database or model needed). Interactive docs at `/docs` exist only outside production.

Generated from the service's own OpenAPI document by `tools/docs/openapi_to_md.py` - 135 operations. Role rules are enforced in code (see the authorization matrix); the `Auth` column shows only whether the OpenAPI document declares a bearer requirement.

## assistant

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/v1/chat` | Chat | bearer | 200,422 |
| POST | `/v1/confirm` | Confirm | bearer | 200,422 |
| GET | `/v1/offers/recommend` | Recommend Checkout Offers | bearer | 200,422 |
| GET | `/v1/recommendations` | Recommendations | bearer | 200,422 |
| GET | `/v1/support/conversation` | Case Conversation | bearer | 200,422 |

## auth

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/v1/me` | Me | bearer | 200 |

## engineering-ai

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/v1/engineering/clusters` | Clusters | bearer | 200,422 |
| GET | `/v1/engineering/deployments` | Deployments | bearer | 200,422 |
| POST | `/v1/engineering/deployments` | Record Deployment | bearer | 201,422 |
| GET | `/v1/engineering/health` | Service Health | bearer | 200,422 |
| GET | `/v1/engineering/incidents` | Incidents | bearer | 200,422 |
| POST | `/v1/engineering/incidents/investigate` | Incident Investigation | bearer | 200,422 |
| POST | `/v1/engineering/incidents/prompt` | Incident Prompt | bearer | 200,422 |
| POST | `/v1/engineering/investigate` | Investigate | bearer | 200,422 |
| GET | `/v1/engineering/logs` | Logs | bearer | 200,422 |
| POST | `/v1/engineering/refresh` | Refresh | bearer | 200,422 |
| GET | `/v1/engineering/sources` | Sources | bearer | 200 |
| GET | `/v1/engineering/trace/{correlation_id}` | Trace | bearer | 200,422 |

## health

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/health` | Health | public/none | 200 |
| GET | `/health/dependencies` | Dependencies | bearer | 200 |

## inventory-intelligence

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/v1/inventory/aging` | Aging | bearer | 200,422 |
| GET | `/v1/inventory/clearance` | Clearance | bearer | 200,422 |
| POST | `/v1/inventory/copilot` | Copilot | bearer | 200,422 |
| GET | `/v1/inventory/events` | Events | bearer | 200 |
| GET | `/v1/inventory/events/{event_id}` | Event Impact | bearer | 200,422 |
| GET | `/v1/inventory/forecast` | Forecast Aggregate | bearer | 200,422 |
| GET | `/v1/inventory/forecast/{product_id}` | Forecast Product | bearer | 200,422 |
| POST | `/v1/inventory/models/train` | Train | bearer | 200 |
| GET | `/v1/inventory/overstock` | Overstock | bearer | 200,422 |
| GET | `/v1/inventory/overview` | Overview | bearer | 200 |
| GET | `/v1/inventory/products` | Products | bearer | 200,422 |
| GET | `/v1/inventory/quality` | Quality | bearer | 200 |
| GET | `/v1/inventory/regions` | Regions | bearer | 200,422 |
| GET | `/v1/inventory/replenishment` | Replenishment | bearer | 200,422 |
| GET | `/v1/inventory/risk` | Risk | bearer | 200,422 |
| POST | `/v1/inventory/scenario` | Scenario | bearer | 200,422 |
| GET | `/v1/inventory/slow-moving` | Slow Moving | bearer | 200,422 |
| GET | `/v1/inventory/taxonomy` | Taxonomy | bearer | 200 |
| GET | `/v1/inventory/usage` | Usage | bearer | 200,422 |

## product-supplier-intelligence

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/v1/sourcing/ask` | Ask | bearer | 200,422 |
| POST | `/v1/sourcing/calculator` | Stateless Calculator | bearer | 200,422 |
| GET | `/v1/sourcing/dashboard` | Dashboard | bearer | 200 |
| POST | `/v1/sourcing/demo/seed` | Seed Demo | bearer | 201 |
| POST | `/v1/sourcing/gaps/{gap_id}/link` | Link Gap | bearer | 200,422 |
| POST | `/v1/sourcing/images/{image_id}/recheck` | Recheck Image | bearer | 200,422 |
| POST | `/v1/sourcing/images/{image_id}/report` | Report Image | bearer | 200,422 |
| GET | `/v1/sourcing/opportunities` | List Opportunities | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities` | Create Opportunity | bearer | 201,422 |
| GET | `/v1/sourcing/opportunities/{opp_id}` | Get Opportunity | bearer | 200,422 |
| PATCH | `/v1/sourcing/opportunities/{opp_id}` | Patch Opportunity | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/calculate` | Calculate | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/compare` | Compare | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/images` | Add Image | bearer | 201,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/images/{image_id}/primary` | Primary Image | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/notes` | Add Note | bearer | 201,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/proposal` | Prepare Proposal | bearer | 201,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/provenance` | Classify | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/quotes` | Add Quote | bearer | 201,422 |
| GET | `/v1/sourcing/opportunities/{opp_id}/readiness` | Get Readiness | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/score` | Rescore | bearer | 200,422 |
| POST | `/v1/sourcing/opportunities/{opp_id}/status` | Change Status | bearer | 200,422 |
| GET | `/v1/sourcing/proposals` | List Proposals | bearer | 200 |
| POST | `/v1/sourcing/proposals/{proposal_id}/review` | Review Proposal | bearer | 200,422 |
| GET | `/v1/sourcing/queue` | Approval Queue | bearer | 200 |
| PATCH | `/v1/sourcing/quotes/{quote_id}` | Patch Quote | bearer | 200,422 |
| POST | `/v1/sourcing/quotes/{quote_id}/verify` | Verify Quote | bearer | 200,422 |
| GET | `/v1/sourcing/runs` | List Runs | bearer | 200,422 |
| POST | `/v1/sourcing/runs` | Start Run | bearer | 202,422 |
| GET | `/v1/sourcing/runs/{run_id}` | Get Run | bearer | 200,422 |
| GET | `/v1/sourcing/runs/{run_id}/results` | Run Results | bearer | 200,422 |
| GET | `/v1/sourcing/sources` | Sources | bearer | 200 |
| GET | `/v1/sourcing/suppliers` | List Suppliers | bearer | 200,422 |
| POST | `/v1/sourcing/suppliers` | Create Supplier | bearer | 201,422 |
| GET | `/v1/sourcing/suppliers/{supplier_id}` | Get Supplier | bearer | 200,422 |
| PATCH | `/v1/sourcing/suppliers/{supplier_id}` | Patch Supplier | bearer | 200,422 |
| POST | `/v1/sourcing/suppliers/{supplier_id}/stage` | Supplier Stage | bearer | 200,422 |

## sales-inventory-planning

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| GET | `/v1/planning/actuals` | Actuals | bearer | 200,422 |
| GET | `/v1/planning/actuals/adjustments` | Adjustments | bearer | 200,422 |
| POST | `/v1/planning/actuals/adjustments` | Adjust Actual | bearer | 201,422 |
| POST | `/v1/planning/actuals/adjustments/{adj_id}/decision` | Decide Adjustment | bearer | 200,422 |
| POST | `/v1/planning/actuals/import` | Import Actuals | bearer | 201,422 |
| POST | `/v1/planning/allocations/{rec_id}/decision` | Decide Allocation | bearer | 200,422 |
| GET | `/v1/planning/analyses/{analysis_id}` | Get Analysis | bearer | 200,422 |
| POST | `/v1/planning/analyses/{analysis_id}/findings/{finding_id}/adopt` | Adopt Finding | bearer | 200,422 |
| POST | `/v1/planning/analyses/{analysis_id}/findings/{finding_id}/dismiss` | Dismiss Finding | bearer | 200,422 |
| GET | `/v1/planning/analysis/regional` | Regional | bearer | 200,422 |
| POST | `/v1/planning/ask` | Ask | bearer | 200,422 |
| GET | `/v1/planning/audit` | Audit | bearer | 200,422 |
| GET | `/v1/planning/dashboard` | Dashboard | bearer | 200,422 |
| POST | `/v1/planning/demo/seed` | Demo Seed | bearer | 201 |
| GET | `/v1/planning/forecast/evaluation` | Evaluation | bearer | 200,422 |
| GET | `/v1/planning/forecast/overview` | Forecast Overview | bearer | 200,422 |
| POST | `/v1/planning/forecast/runs` | Forecast Run | bearer | 201,422 |
| GET | `/v1/planning/forecast/series` | Forecast Series | bearer | 200,422 |
| POST | `/v1/planning/ingest` | Ingest | bearer | 202,422 |
| GET | `/v1/planning/ingest/runs` | Ingest Runs | bearer | 200 |
| GET | `/v1/planning/locations` | Locations | bearer | 200 |
| GET | `/v1/planning/pilots` | Pilots | bearer | 200 |
| POST | `/v1/planning/pilots` | Propose Pilot | bearer | 201,422 |
| GET | `/v1/planning/pilots/{pid}/assessment` | Pilot Assessment | bearer | 200,422 |
| POST | `/v1/planning/pilots/{pid}/decision` | Pilot Decision | bearer | 200,422 |
| POST | `/v1/planning/pilots/{pid}/observations` | Pilot Observations | bearer | 200,422 |
| GET | `/v1/planning/plans` | List Plans | bearer | 200,422 |
| POST | `/v1/planning/plans` | Create Plan | bearer | 201,422 |
| GET | `/v1/planning/plans/{plan_id}` | Get Plan | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/allocations` | Allocations | bearer | 200,422 |
| POST | `/v1/planning/plans/{plan_id}/allocations/generate` | Generate Allocation | bearer | 201,422 |
| GET | `/v1/planning/plans/{plan_id}/analysis` | Latest Analysis | bearer | 200,422 |
| POST | `/v1/planning/plans/{plan_id}/analysis` | Analyze Plan | bearer | 201,422 |
| GET | `/v1/planning/plans/{plan_id}/audit` | Plan Audit | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/breakdown` | Plan Breakdown | bearer | 200,422 |
| POST | `/v1/planning/plans/{plan_id}/growth-targets` | Growth Targets | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/impact` | Impact | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/investigate` | Investigate | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/lines` | Plan Lines | bearer | 200,422 |
| PATCH | `/v1/planning/plans/{plan_id}/lines` | Edit Lines | bearer | 200,422 |
| GET | `/v1/planning/plans/{plan_id}/performance` | Performance | bearer | 200,422 |
| POST | `/v1/planning/plans/{plan_id}/revise` | Revise | bearer | 201,422 |
| POST | `/v1/planning/plans/{plan_id}/transition` | Transition | bearer | 200,422 |
| GET | `/v1/planning/readiness` | Readiness | bearer | 200 |
| POST | `/v1/planning/review/bulk` | Bulk Review | bearer | 200,422 |
| GET | `/v1/planning/review/queue` | Review Queue | bearer | 200,422 |
| GET | `/v1/planning/scenarios` | Scenarios | bearer | 200,422 |
| POST | `/v1/planning/scenarios` | Create Scenario | bearer | 201,422 |
| POST | `/v1/planning/scenarios/compare` | Compare | bearer | 200,422 |
| GET | `/v1/planning/scenarios/{sid}` | Scenario | bearer | 200,422 |
| POST | `/v1/planning/scenarios/{sid}/adopt` | Adopt | bearer | 200,422 |
| POST | `/v1/planning/scope` | Scope | bearer | 200,422 |
| GET | `/v1/planning/taxonomy` | Taxonomy | bearer | 200 |
| POST | `/v1/planning/transfers/{rec_id}/decision` | Decide Transfer | bearer | 200,422 |

## support-staff-ai

| Method | Path | Summary | Auth | Responses |
|---|---|---|---|---|
| POST | `/v1/support/feedback` | Feedback | bearer | 204,422 |
| GET | `/v1/support/staff/cases/{ref}/actions` | Actions | bearer | 200,422 |
| POST | `/v1/support/staff/cases/{ref}/actions/{action_id}` | Record | bearer | 200,422 |
| POST | `/v1/support/staff/cases/{ref}/assist` | Assist | bearer | 200,422 |
| GET | `/v1/support/staff/insights` | Insights | bearer | 200,422 |

