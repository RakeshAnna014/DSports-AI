"""DEMO DATA ONLY. Prepares one customer whose orders exercise the Return AI scenarios the backend can really express today.

Everything goes through the REAL DSports APIs (register, cart, checkout, order, payment, shipment steps, return request). The only direct database
write is one clearly labelled SYNTHETIC back-dating for the "expired window" scenario: the order has no delivery timestamp, so the policy uses the
last status change of a delivered order (see ReturnsModuleConfig); a real order would need to age by waiting. Idempotent: a customer who already
has the scenario orders is left alone. Nothing else in the database is touched; real users' data is never reset.

    python e2e/ai/seed_return_scenarios.py            # prints the customer, the order numbers and what each one demonstrates

| Label            | State                                                  | What Return AI should say / do                                         |
| R1-eligible      | delivered now, one item                                | eligible: damaged, wrong item, missing item or change of mind           |
| R2-partial       | delivered now, quantity 2 of one product               | eligible for 1 or 2 units; asking for 3 is refused                      |
| R3-expired       | delivered 10 days ago (SYNTHETIC back-dating)          | not eligible: the return window (default 7 days, app.returns.window)    |
| R4-in-transit    | out for delivery                                       | not eligible yet: not delivered                                         |
| R5-already       | delivered, return already requested through the API    | a second request is refused (already returned)                          |
| R6-undispatched  | paid and confirmed, nothing dispatched                 | no return; a refund request is the supported path (mock payment)         |
| R7-failed        | delivery attempt failed                                | not delivered: tracking shows the failure and the reason                |

NOT supported by the backend today (so no scenario is faked): exchange, refund to a wallet, retention offers, grocery refund without collection,
pickup-required / inspection-required / store returns. They need new domain rules and business decisions: see docs/ai/16-demo-fulfilment-and-return-scenarios.md.
"""
import json
import sys
import time

import stack as s
from seed_demo_customers import buy_many, product_ids

EMAIL = "demo.returns@example.com"
OPERATOR = "inventory.admin@example.com"       # the existing demo administrator (ADMIN role); created below if missing
BAT, GRIP_OR_BALL = "MRF Cricket Bat - Virgin", "MRF Cricket Gloves - Pro"
SHIPMENT_STEPS = ["READY_FOR_PICKUP", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"]


def operator_token() -> str:
    st, _ = s.dsports("POST", "/api/auth/register", body={"email": OPERATOR, "password": s.PASSWORD, "firstName": "Demo", "lastName": "Operator"})
    return s.make_admin(OPERATOR)           # same SQL role grant the other harnesses use: no admin provisioning API exists


def shipment_of(order_id: str) -> str:
    for _ in range(40):                     # created asynchronously through the outbox after the payment is captured
        ship = s.sql(f"select id from shipments where order_id='{order_id}'")
        if ship:
            return ship
        time.sleep(0.5)
    raise AssertionError("the shipment was not created")


def advance(admin: str, ship: str, until: str) -> None:
    current = s.sql(f"select status from shipments where id='{ship}'")
    start = SHIPMENT_STEPS.index(current) + 1 if current in SHIPMENT_STEPS else 0
    for step in SHIPMENT_STEPS[start: SHIPMENT_STEPS.index(until) + 1]:
        st, r = s.dsports("PUT", f"/api/v1/shipments/admin/{ship}/status", admin, {"newStatus": step}); assert st == 200, (step, st, r)


def order_of(token: str, names: list[str], ids: dict[str, str], quantity: int = 1) -> dict:
    if quantity > 1:
        st, _ = s.dsports("POST", "/api/v1/cart/items", token, {"productId": ids[names[0]], "quantity": quantity - 1}); assert st == 200, st
    order = buy_many(token, [ids[n] for n in names])
    s.pay(token, order)
    return order


def main() -> None:
    ids = product_ids()
    s.restock()
    admin = operator_token()
    st, _ = s.dsports("POST", "/api/auth/register", body={"email": EMAIL, "password": s.PASSWORD, "firstName": "Riya", "lastName": "Returns"})
    token = s.login(EMAIL)
    st, existing = s.dsports("GET", "/api/v1/orders", token)
    if st == 200 and len(existing) >= 7:
        print(json.dumps({"email": EMAIL, "password": s.PASSWORD, "created": False, "orders": [o["orderNumber"] for o in existing]}, indent=1))
        return
    made = []

    def record(label: str, order: dict, note: str) -> None:
        made.append({"label": label, "orderNumber": order["orderNumber"], "orderId": order["id"], "note": note})

    o = order_of(token, [BAT], ids); advance(admin, shipment_of(o["id"]), "DELIVERED"); record("R1-eligible", o, "delivered now")
    o = order_of(token, [GRIP_OR_BALL], ids, quantity=2); advance(admin, shipment_of(o["id"]), "DELIVERED"); record("R2-partial", o, "delivered, quantity 2")
    o = order_of(token, [BAT], ids); advance(admin, shipment_of(o["id"]), "DELIVERED")
    s.sql(f"update orders set updated_at = now() - interval '10 days' where id = '{o['id']}'")       # SYNTHETIC back-dating (see the module docstring)
    record("R3-expired", o, "delivered 10 days ago (synthetic back-dating)")
    o = order_of(token, [GRIP_OR_BALL], ids); advance(admin, shipment_of(o["id"]), "OUT_FOR_DELIVERY"); record("R4-in-transit", o, "out for delivery")
    o = order_of(token, [BAT], ids); advance(admin, shipment_of(o["id"]), "DELIVERED")
    st, ret = s.dsports("POST", "/api/v1/returns", token, {"orderId": o["id"], "productId": ids[BAT], "quantity": 1, "reason": "Demo: the bat arrived with a cracked handle"},
                        {"Idempotency-Key": f"seed-return-{o['id']}"}); assert st == 201, (st, ret)
    record("R5-already", o, f"return {ret.get('id')} already requested")
    o = order_of(token, [GRIP_OR_BALL], ids); shipment_of(o["id"]); record("R6-undispatched", o, "paid and confirmed, nothing dispatched")
    o = order_of(token, [BAT], ids); ship = shipment_of(o["id"]); advance(admin, ship, "PICKED_UP")
    st, r = s.dsports("PUT", f"/api/v1/shipments/admin/{ship}/status", admin, {"newStatus": "FAILED", "reason": "Demo: customer not available at the address"}); assert st == 200, (st, r)
    record("R7-failed", o, "delivery attempt failed")
    print(json.dumps({"email": EMAIL, "password": s.PASSWORD, "created": True, "operator": OPERATOR, "orders": made}, indent=1))


if __name__ == "__main__":
    sys.exit(main())
