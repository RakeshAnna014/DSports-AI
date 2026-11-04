"""Checks that the seeded Return AI scenarios behave as documented, using the authoritative eligibility endpoint (no mocks).

    python e2e/ai/seed_return_scenarios.py && python e2e/ai/verify_return_scenarios.py
"""
import sys

import stack as s

EMAIL = "demo.returns@example.com"
EXPECT = {                     # label -> (eligible, reason code)
    "R1": (True, "ELIGIBLE"), "R2": (True, "ELIGIBLE"), "R3": (False, "RETURN_WINDOW_EXPIRED"), "R4": (False, "ORDER_NOT_DELIVERED"),
    "R5": (False, "ALREADY_RETURNED"), "R6": (False, "ORDER_NOT_DELIVERED"), "R7": (False, "ORDER_NOT_DELIVERED"),
}
token = s.login(EMAIL)
st, orders = s.dsports("GET", "/api/v1/orders", token)
assert st == 200 and len(orders) >= 7, (st, orders)
failures = []
by_number = sorted(orders, key=lambda o: o["orderNumber"])[-7:]
for label, o in zip(sorted(EXPECT), by_number):
    st, full = s.dsports("GET", f"/api/v1/orders/{o['id']}", token)
    product = full["items"][0]["productId"]
    st, el = s.dsports("GET", f"/api/v1/orders/{o['id']}/returns/eligibility?productId={product}", token)
    want = EXPECT[label]
    got = (el.get("eligible"), el.get("reason"))
    ok = st == 200 and got == want
    print(("PASS " if ok else "FAIL ") + f"{label} {o['orderNumber']}: eligible={got[0]} reason={got[1]} (expected {want})")
    if not ok:
        failures.append(label)
st, ship = s.dsports("GET", f"/api/v1/shipments/by-order/{by_number[6]['id']}", token)
print(("PASS " if st == 200 and ship.get("shipment", {}).get("status") == "FAILED" else "FAIL ") + f"R7 tracking shows the failed delivery: {ship.get('shipment', {}).get('status') if st == 200 else st}")
if st != 200 or ship.get("shipment", {}).get("status") != "FAILED":
    failures.append("R7-tracking")
print("RESULT:", "FAIL " + str(failures) if failures else "ALL PASS")
sys.exit(1 if failures else 0)
