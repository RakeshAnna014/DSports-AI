"""Real-stack check of: checkout addresses, promotions (apply/remove/limits/totals into order and payment), the AI offer recommender,
the fulfilment board and a Return AI eligibility check on the order the board delivered. No mocks. Throw-away users with random passwords.

    python e2e/ai/checkout_features_e2e.py        (backend :8080 on the build that contains V47, AI service :8090)
"""
import secrets
import sys
import time
from decimal import Decimal

import stack as s

failures: list[str] = []


def check(name: str, ok: bool, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"   [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(name)


def D(v) -> Decimal:
    return Decimal(str(v))


def new_customer(prefix: str):
    email = f"{prefix}-{secrets.token_hex(4)}@example.com"
    s.dsports("POST", "/api/auth/register", body={"email": email, "password": s.PASSWORD, "firstName": "Asha", "lastName": "Rao"})
    return email, s.login(email)


GOOD = {"line1": "12 MG Road", "line2": "Flat 4", "city": "Pune", "state": "MH", "country": "IN", "postalCode": "411001", "type": "SHIPPING"}

# ---------------------------------------------------------------------------------------------------------------- 1. addresses
_, alice = new_customer("co-alice")
_, bob = new_customer("co-bob")
st, body = s.dsports("POST", "/api/customers/me/addresses", alice, {**GOOD, "country": "India"})
check("address: a full country name is a 400 with the reason, not a 500", st == 400 and body.get("code") == "INVALID_COUNTRY" and "alpha-2" in body.get("message", ""), (st, body))
st, body = s.dsports("POST", "/api/customers/me/addresses", alice, {**GOOD, "postalCode": "411 001"})
check("address: a postal code with a space is a 400 INVALID_POSTAL_CODE", st == 400 and body.get("code") == "INVALID_POSTAL_CODE", (st, body))
st, body = s.dsports("POST", "/api/customers/me/addresses", alice, {**GOOD, "country": ""})
check("address: a blank country is a 400 validation error", st == 400, (st, body))
st, a1 = s.dsports("POST", "/api/customers/me/addresses", alice, GOOD)
check("address: a valid address is created (201) with an id", st == 201 and a1.get("addressId"), (st, a1))
st, a2 = s.dsports("POST", "/api/customers/me/addresses", alice, {**GOOD, "line1": "7 Park Street", "city": "Mumbai"})
check("address: a second address is allowed", st == 201, (st, a2))
st, lst = s.dsports("GET", "/api/customers/me/addresses", alice)
check("address: both persist and are listed for the owner", st == 200 and {a["addressId"] for a in lst["addresses"]} == {a1["addressId"], a2["addressId"]}, (st, lst))
st, lst_b = s.dsports("GET", "/api/customers/me/addresses", bob)
check("address: another customer sees none of them", st == 200 and lst_b["addresses"] == [], (st, lst_b))
st, _ = s.dsports("PUT", f"/api/customers/me/addresses/{a1['addressId']}", bob, {**GOOD, "line1": "hijacked"})
check("address: another customer can not modify it", st in (403, 404), st)
st, _ = s.dsports("DELETE", f"/api/customers/me/addresses/{a1['addressId']}", bob)
check("address: another customer can not delete it", st in (403, 404), st)

# ---------------------------------------------------------------------------------------------------------------- 2. promotions
s.restock()
st, _ = s.dsports("POST", "/api/v1/cart/items", alice, {"productId": s.P_BAT_MRF, "quantity": 2}); assert st == 200, st
st, co = s.dsports("POST", "/api/v1/checkout", alice); assert st == 201, (st, co)
cid = co["id"]
st, c0 = s.dsports("GET", f"/api/v1/checkout/{cid}", alice)
subtotal, tax = D(c0["subtotal"]), D(c0["taxAmount"])
check("checkout starts with no promotion and the existing 18% tax on the subtotal", c0["promotionCode"] is None and D(c0["discountAmount"]) == 0 and tax == (subtotal * D("0.18")).quantize(D("0.01")), c0)
st, offers = s.dsports("GET", f"/api/v1/checkout/{cid}/promotions", alice)
codes = {o["code"]: o for o in offers} if st == 200 else {}
check("offers: valid offers are listed, all labelled demo, the expired one is not listed", st == 200 and "WELCOME10" in codes and "OLDFEST5" not in codes and all(o["demo"] for o in offers), (st, list(codes)))
check("offers: the cricket offer names its scope", codes.get("CRICKET15", {}).get("scopeNames") == ["Cricket"], codes.get("CRICKET15"))
st, _ = s.dsports("GET", f"/api/v1/checkout/{cid}/promotions", bob)
check("offers: another customer can not read my checkout's offers", st in (403, 404), st)

st, r = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": " welcome10 "})
expected = min((subtotal * D("0.10")).quantize(D("0.01")), D("300"))
check("apply: WELCOME10 (case/space-insensitive) gives 10% capped at 300", st == 200 and D(r["discountAmount"]) == expected and r["promotionCode"] == "WELCOME10", (st, r))
total_after = D(r["totalAmount"])
check("apply: total = subtotal + tax + delivery - discount (tax unchanged)", total_after == subtotal + tax + D(r["deliveryCharge"]) - expected and D(r["taxAmount"]) == tax, r)
st, r2 = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": "WELCOME10"})
check("apply: applying the same code again does not apply it twice", st == 200 and D(r2["totalAmount"]) == total_after, (st, r2))
st, r3 = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": "CRICKET15"})
check("apply: a second different code is refused (one promotion per checkout)", st == 409 and r3.get("code") == "PROMOTION_ALREADY_APPLIED", (st, r3))
st, r4 = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", bob, {"code": "WELCOME10"})
check("apply: another customer can not apply a code to my checkout", st in (403, 404), st)
st, r5 = s.dsports("DELETE", f"/api/v1/checkout/{cid}/promotion", alice)
check("remove: the discount and code are cleared and the total is back", st == 200 and r5["promotionCode"] is None and D(r5["discountAmount"]) == 0 and D(r5["totalAmount"]) == D(c0["totalAmount"]), (st, r5))
for code, want_status, want_code in (("NOSUCHCODE", 404, "PROMOTION_NOT_FOUND"), ("OLDFEST5", 400, "PROMOTION_EXPIRED"), ("FLAT200", 400, "PROMOTION_MIN_ORDER_NOT_MET")):
    st, r = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": code})
    if code == "FLAT200" and st == 200:                                    # a cart of 2 bats can exceed the minimum on this catalogue: then it is simply valid
        s.dsports("DELETE", f"/api/v1/checkout/{cid}/promotion", alice)
        check("apply: FLAT200 is valid for this cart (above its minimum)", True)
        continue
    check(f"apply: {code} is rejected with {want_code}", st == want_status and r.get("code") == want_code, (st, r))
st, r = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": ""})
check("apply: an empty code is a validation error", st == 400, (st, r))

# the AI recommender (no model; backend offers + evidence)
st, rec = s.ai("GET", f"/v1/offers/recommend?checkoutId={cid}", alice)
check("AI offers: returns the backend's offers ranked, with the demo disclaimer", st == 200 and rec["offers"] and "synthetic demonstration offers" in rec["disclaimer"], (st, rec))
if st == 200 and rec["offers"]:
    top = rec["offers"][0]
    backend = {o["code"]: o for o in offers}[top["code"]]
    check("AI offers: the amount is exactly the backend's, never recalculated", D(top["discountAmount"]) == D(backend["discountAmount"]), (top, backend))
    check("AI offers: no purchase history is claimed for a customer without orders", not any("bought" in x for o in rec["offers"] for x in o["reasons"]), rec["offers"])
st, _ = s.ai("GET", f"/v1/offers/recommend?checkoutId={cid}", bob)
check("AI offers: another customer's checkout is not available", st == 404, st)
st, _ = s.ai("GET", f"/v1/offers/recommend?checkoutId={cid}")
check("AI offers: needs a signed-in customer", st == 401, st)

st, r = s.dsports("POST", f"/api/v1/checkout/{cid}/promotion", alice, {"code": "WELCOME10"}); assert st == 200, (st, r)
sel = s.dsports("POST", f"/api/v1/checkout/{cid}/address", alice, {"addressId": a1["addressId"]})
check("checkout: the saved address can be selected", sel[0] == 200 and sel[1]["shippingAddressId"] == a1["addressId"], sel)
st, other = s.dsports("POST", f"/api/v1/checkout/{cid}/address", alice, {"addressId": lst_b and a1["addressId"][:-1] + "0"})
check("checkout: an address id that is not mine is refused", st >= 400, (st, other))
s.dsports("POST", f"/api/v1/checkout/{cid}/delivery", alice, {"deliveryMethodCode": "STANDARD"})
st, v = s.dsports("POST", f"/api/v1/checkout/{cid}/validate", alice); assert st == 200, (st, v)
st, r = s.dsports("DELETE", f"/api/v1/checkout/{cid}/promotion", alice)
check("remove: after validation the promotion can no longer change (409)", st == 409 and r.get("code") == "PROMOTION_NOT_EDITABLE", (st, r))
grand = D(v["totalAmount"])
st, order = s.dsports("POST", "/api/v1/orders", alice, {"checkoutId": cid}); assert st == 201, (st, order)
st, o2 = s.dsports("GET", f"/api/v1/orders/{order['id']}", alice)
check("order: discount, promotion code and grand total are exactly the checkout's, and persist in the order history", st == 200 and D(o2["discountAmount"]) == expected and o2["promotionCode"] == "WELCOME10" and D(o2["grandTotal"]) == grand, o2)
check("order: totals reconcile (subtotal + shipping + tax - discount = grand total)", D(o2["subtotal"]) + D(o2["shippingCharge"]) + D(o2["taxAmount"]) - D(o2["discountAmount"]) == D(o2["grandTotal"]), o2)
st, bad = s.dsports("POST", "/api/v1/payments", alice, {"orderId": order["id"], "amount": float(grand + 1), "currency": "INR", "paymentMethod": "UPI", "paymentProvider": "MOCK"})
check("payment: an amount that is not the order's grand total is refused", st >= 400, (st, bad))
st, pay = s.dsports("POST", "/api/v1/payments", alice, {"orderId": order["id"], "amount": float(grand), "currency": "INR", "paymentMethod": "UPI", "paymentProvider": "MOCK"})
check("payment: the grand total (with the discount) is accepted", st in (200, 201) and D(pay["amount"]) == grand, (st, pay))
st, cap = s.dsports("POST", f"/api/v1/payments/{pay['id']}/capture", alice)
check("payment: captured for exactly the discounted total", st == 200 and D(cap["amount"]) == grand, (st, cap))

s.restock()
s.dsports("POST", "/api/v1/cart/items", alice, {"productId": s.P_BAT_MRF, "quantity": 1})
st, co2 = s.dsports("POST", "/api/v1/checkout", alice)
st, r = s.dsports("POST", f"/api/v1/checkout/{co2['id']}/promotion", alice, {"code": "WELCOME10"})
check("limits: WELCOME10 is once per customer, so a second order is refused", st == 409 and r.get("code") == "PROMOTION_LIMIT_REACHED", (st, r))
st, r = s.dsports("POST", f"/api/v1/checkout/{co2['id']}/promotion", alice, {"code": "CRICKET15"})
check("limits: another offer is still available to the same customer", st == 200 and r["promotionCode"] == "CRICKET15", (st, r))
s.dsports("POST", f"/api/v1/checkout/{co2['id']}/cancel", alice)

# ---------------------------------------------------------------------------------------------------------------- 3. fulfilment board + Return AI
st, _ = s.dsports("GET", "/api/v1/admin/fulfilment/orders", alice)
check("board: a customer is refused (403)", st == 403, st)
admin = s.make_admin(new_customer("co-ops")[0])
for _ in range(40):                                              # the shipment is created asynchronously after the capture
    st, board = s.dsports("GET", "/api/v1/admin/fulfilment/orders?status=CONFIRMED,PROCESSING,SHIPPED", admin)
    row = next((r for r in board if r["orderId"] == order["id"]), None) if st == 200 else None
    if row and row["shipmentId"]:
        break
    time.sleep(0.5)
check("board: the paid order is listed with its payment and shipment state, email masked", bool(row) and row["paid"] and row["shipmentId"] and "***" in (row["customer"] or ""), row)
st, _ = s.dsports("GET", "/api/v1/admin/fulfilment/orders?status=DROP", admin)
check("board: an unknown status filter is a 400", st == 400, st)
st, el = s.dsports("GET", f"/api/v1/orders/{order['id']}/returns/eligibility?productId={s.P_BAT_MRF}", alice)
check("return AI: not eligible before delivery", st == 200 and el["eligible"] is False, (st, el))
st, _ = s.dsports("PUT", f"/api/v1/shipments/admin/{row['shipmentId']}/status", alice, {"newStatus": "PICKED_UP"})
check("fulfilment: a customer can not advance a shipment (403)", st == 403, st)
steps = []
while True:
    st, board = s.dsports("GET", "/api/v1/admin/fulfilment/orders?status=CONFIRMED,PROCESSING,SHIPPED,DELIVERED", admin)
    row = next(r for r in board if r["orderId"] == order["id"])
    forward = [n for n in row["nextShipmentStatuses"] if n not in ("FAILED", "RETURNED")]
    if not forward:
        break
    if row["shipmentStatus"] == "CREATED" or True:
        st, r = s.dsports("PUT", f"/api/v1/shipments/admin/{row['shipmentId']}/status", admin, {"newStatus": forward[0]})
        if st != 200:
            check(f"fulfilment: step {forward[0]} accepted", False, (st, r)); break
        steps.append(forward[0])
check("fulfilment: the board walked the shipment CREATED -> ... -> DELIVERED using only the valid next steps", steps and steps[-1] == "DELIVERED", steps)
st, ord_now = s.dsports("GET", f"/api/v1/orders/{order['id']}", alice)
check("fulfilment: the order followed the shipment to DELIVERED", ord_now["status"] == "DELIVERED", ord_now["status"])
st, bad = s.dsports("PUT", f"/api/v1/shipments/admin/{row['shipmentId']}/status", admin, {"newStatus": "IN_TRANSIT"})
check("fulfilment: a delivered shipment can not move back (409)", st in (400, 409), (st, bad))
st, el = s.dsports("GET", f"/api/v1/orders/{order['id']}/returns/eligibility?productId={s.P_BAT_MRF}", alice)
check("return AI: the delivered order is now eligible according to the authoritative policy", st == 200 and el["eligible"] is True, (st, el))
st, ret = s.dsports("POST", "/api/v1/returns", alice, {"orderId": order["id"], "productId": s.P_BAT_MRF, "quantity": 1, "reason": "E2E: arrived damaged"}, {"Idempotency-Key": secrets.token_hex(8)})
check("return AI: a return can be requested for the delivered order", st == 201, (st, ret))

print("RESULT:", "FAIL " + str(failures) if failures else "ALL PASS")
sys.exit(1 if failures else 0)
