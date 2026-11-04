# 16. Demo fulfilment console and Return AI scenarios

**Status: IMPLEMENTED / PARTIALLY VERIFIED. DEMO DATA ONLY.** The console and the seeder exist to let a presenter move *real* paid orders through delivery so Return AI has something to work on. Nothing here is a warehouse system.

## What was built

| Piece | Where | Notes |
|---|---|---|
| Fulfilment board API | `GET /api/v1/admin/fulfilment/orders` (`FulfilmentBoardController` in `backend/bootstrap`) | ADMIN only (enforced by the backend). Customer e-mail addresses are masked. |
| Shipment status changes | existing `PUT /api/v1/shipments/admin/{id}/status` | The console calls the existing endpoint. The backend keeps the order status in step with the shipment. |
| Console UI | `/fulfilment` (`frontend/src/features/fulfilment/FulfilmentApp.tsx`) | Hidden from non-admins in the UI; the real check is the backend on every request. |
| Scenario seeder | `e2e/ai/seed_return_scenarios.py` | Creates `demo.returns@example.com` and seven orders through the real APIs. Idempotent. |
| Scenario verifier | `e2e/ai/verify_return_scenarios.py` | Asks the real assistant about each scenario and checks the answer against the backend's own eligibility. |

## Scenarios the backend can really express

| Label | State | What Return AI says |
|---|---|---|
| R1-eligible | delivered now, one item | eligible |
| R2-partial | delivered now, quantity 2 | eligible for 1 or 2 units; 3 is refused |
| R3-expired | delivered 10 days ago | not eligible: return window (default 7 days, `app.returns.window`) |
| R4-in-transit | out for delivery | not eligible yet: not delivered |
| R5-already | return already requested | a second request is refused |
| R6-undispatched | paid and confirmed, nothing dispatched | no return; a refund request is the supported path |
| R7-failed | delivery attempt failed | not delivered; tracking shows the failure |

R3 is the one place the data is **SYNTHETIC**: the order is back-dated by one labelled SQL update because a real order would have to age for ten days. Everything else goes through public APIs. Real users' data is never reset.

## Not supported by the backend today (no scenario is faked)

Exchange as a flow, refund to a wallet, retention offers, grocery refund without collection, pickup-required, inspection-required and store returns. Each needs new domain rules **and a business decision**. Status: NOT IMPLEMENTED, REQUIRES BUSINESS DECISION. The assistant explains that an exchange is a return plus a new order; it does not pretend a flow exists.

## Verification

`python e2e/ai/verify_return_scenarios.py` against the running stack reported ALL PASS when it was last run (earlier in this work). It must be re-run in the consolidated validation pass after the final restart.
