"""A REAL support 'world' on the running stack for the Phase 3 scenarios, evaluation and browser journeys.

Every customer, order, payment, shipment, return and refund is created through the real DSports APIs (register, cart, checkout, pay, admin shipment status,
return, admin decision, refund request). Only the TIME of a few records is adjusted with SQL (to make a shipment stalled or a delivery old), exactly like
stack.restock adjusts stock: there is no API to travel back in time. Nothing in the world is mocked.

Usage: python e2e/ai/support_data.py [--only name,name]  -> JSON {password, admin, support, customers: {name: {email, orders: {...}}}}
"""
from __future__ import annotations

import json
import sys
import time
import uuid

import stack as s

PASSWORD = s.PASSWORD


def _grant(email: str, role: str) -> str:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")
    return s.login(email)


def _ship_to(admin: str, order_id: str, statuses: tuple[str, ...]) -> str:
    for _ in range(30):
        ship = s.sql(f"select id from shipments where order_id='{order_id}'")
        if ship:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("shipment was not created")
    for st in statuses:
        code, r = s.dsports("PUT", f"/api/v1/shipments/admin/{ship}/status", admin, {"newStatus": st}); assert code == 200, (st, code, r)
    return ship


def _age_shipment(ship_id: str, event_days_ago: int, eta_days_ago: int | None) -> None:
    s.sql(f"update shipment_tracking set event_time = now() - interval '{event_days_ago} days' where shipment_id='{ship_id}'")
    if eta_days_ago is not None:
        s.sql(f"update shipments set estimated_delivery_date = now() - interval '{eta_days_ago} days' where id='{ship_id}'")
    else:
        s.sql(f"update shipments set estimated_delivery_date = now() + interval '4 days' where id='{ship_id}'")


def _customer(prefix: str) -> tuple[str, str]:
    return s.register(prefix)


def _payment_of(order_id: str, token: str) -> dict:
    code, rows = s.dsports("GET", "/api/v1/payments/history", token); assert code == 200, rows
    return next(p for p in rows if p["orderId"] == order_id)


def buy_lines(token: str, lines: list[tuple[str, int]]) -> dict:
    """One real order with several different products (stack.buy takes one)."""
    for pid, qty in lines:
        code, _ = s.dsports("POST", "/api/v1/cart/items", token, {"productId": pid, "quantity": qty}); assert code == 200, code
    code, addr = s.dsports("POST", "/api/customers/me/addresses", token, {"line1": "1 MG Road", "line2": "Flat 4", "city": "Bengaluru", "state": "KA",
                           "country": "IN", "postalCode": "560001", "type": "SHIPPING"}); assert code == 201, (code, addr)
    code, co = s.dsports("POST", "/api/v1/checkout", token); assert code == 201, (code, co)
    for path, body in ((f"/api/v1/checkout/{co['id']}/address", {"addressId": addr["addressId"]}), (f"/api/v1/checkout/{co['id']}/delivery", {"deliveryMethodCode": "STANDARD"}),
                       (f"/api/v1/checkout/{co['id']}/validate", None)):
        code, r = s.dsports("POST", path, token, body); assert code == 200, (path, code, r)
    code, order = s.dsports("POST", "/api/v1/orders", token, {"checkoutId": co["id"]}); assert code == 201, (code, order)
    return order


def build_many(copies: int, only: set[str] | None = None) -> dict:
    """`copies` independent sets of the same scenarios (keys name, name2, name3 ...): evaluation cases that create cases must not share a customer."""
    merged = build(only)
    for i in range(2, copies + 1):
        extra = build(only, suffix=str(i))
        merged["customers"].update(extra["customers"])
    return merged


def build(only: set[str] | None = None, suffix: str = "") -> dict:
    s.restock(800)
    admin_email, _ = s.register("sup-admin")
    admin = s.make_admin(admin_email)
    staff_email, _ = s.register("sup-staff")
    _grant(staff_email, "SUPPORT_EXECUTIVE")
    world: dict = {"password": PASSWORD, "admin": {"email": admin_email}, "support": {"email": staff_email}, "customers": {}}

    def want(name: str) -> bool:
        return only is None or name in only

    def add(name: str, email: str, **orders) -> None:
        world["customers"][name + suffix] = {"email": email, "orders": orders}

    if want("missing"):                      # shipment DELIVERED by the system, customer says it never arrived
        email, tok = _customer("sup-missing")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        add("missing", email, order=o["orderNumber"], orderId=o["id"])
    if want("transit"):                      # moving, inside its estimate
        email, tok = _customer("sup-transit")
        o = s.buy(tok, s.P_BAT_SS, 1); s.pay(tok, o)
        ship = _ship_to(admin, o["id"], ("PICKED_UP", "IN_TRANSIT")); _age_shipment(ship, 0, None)
        add("transit", email, order=o["orderNumber"], orderId=o["id"])
    if want("stalled"):                      # no movement for six days and past its estimate
        email, tok = _customer("sup-stalled")
        o = s.buy(tok, s.P_BAT_SG, 1); s.pay(tok, o)
        ship = _ship_to(admin, o["id"], ("PICKED_UP", "IN_TRANSIT")); _age_shipment(ship, 6, 2)
        add("stalled", email, order=o["orderNumber"], orderId=o["id"])
    if want("damaged"):                      # two delivered orders so the customer must say which product is damaged
        email, tok = _customer("sup-damaged")
        a = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        b = s.delivered_order(tok, admin, s.P_SHOE_DSC, 1)
        add("damaged", email, bat=a["orderNumber"], batId=a["id"], shoes=b["orderNumber"], shoesId=b["id"])
    if want("paycancel"):                    # a payment attempt that was cancelled; the order is still waiting for payment
        email, tok = _customer("sup-paycancel")
        o = s.buy(tok, s.P_BAT_MRF, 1)
        code, p = s.dsports("POST", "/api/v1/payments", tok, {"orderId": o["id"], "amount": o["grandTotal"], "currency": "INR", "paymentMethod": "UPI", "paymentProvider": "MOCK"})
        assert code in (200, 201), (code, p)
        code, r = s.dsports("POST", f"/api/v1/payments/{p['id']}/cancel", tok); assert code == 200, (code, r)
        add("paycancel", email, order=o["orderNumber"], orderId=o["id"])
    if want("twoattempts"):                  # first attempt cancelled, second captured: exactly one successful payment (a double charge cannot exist: unique index)
        email, tok = _customer("sup-twoattempts")
        o = s.buy(tok, s.P_BAT_SS, 1)
        code, p = s.dsports("POST", "/api/v1/payments", tok, {"orderId": o["id"], "amount": o["grandTotal"], "currency": "INR", "paymentMethod": "UPI", "paymentProvider": "MOCK"})
        assert code in (200, 201), (code, p)
        code, r = s.dsports("POST", f"/api/v1/payments/{p['id']}/cancel", tok); assert code == 200, (code, r)
        s.pay(tok, o)
        add("twoattempts", email, order=o["orderNumber"], orderId=o["id"])
    if want("refundnone"):                   # return approved, nobody has started a refund
        email, tok = _customer("sup-refundnone")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        code, r = s.dsports("POST", "/api/v1/returns", tok, {"orderId": o["id"], "productId": s.P_BAT_MRF, "quantity": 1, "reason": "Changed my mind"},
                            headers={"Idempotency-Key": f"sup-{uuid.uuid4()}"}); assert code == 201, (code, r)
        code, r2 = s.dsports("POST", f"/api/v1/admin/returns/{r['id']}/approve", admin, {"note": "Approved"}); assert code == 200, (code, r2)
        add("refundnone", email, order=o["orderNumber"], orderId=o["id"], returnId=r["id"])
    if want("refundpending"):                # the customer asked for a refund and it is waiting for review
        email, tok = _customer("sup-refundpending")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        p = _payment_of(o["id"], tok)
        code, r = s.dsports("POST", f"/api/v1/payments/{p['id']}/refund-requests", tok, {"reason": "Return approved"}, headers={"Idempotency-Key": f"sup-{uuid.uuid4()}"})
        assert code == 201, (code, r)
        add("refundpending", email, order=o["orderNumber"], orderId=o["id"], paymentId=p["id"])
    if want("returnrejected"):
        email, tok = _customer("sup-rejected")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        code, r = s.dsports("POST", "/api/v1/returns", tok, {"orderId": o["id"], "productId": s.P_BAT_MRF, "quantity": 1, "reason": "Not what I expected"},
                            headers={"Idempotency-Key": f"sup-{uuid.uuid4()}"}); assert code == 201, (code, r)
        code, r2 = s.dsports("POST", f"/api/v1/admin/returns/{r['id']}/reject", admin, {"note": "Item shows signs of use"}); assert code == 200, (code, r2)
        add("returnrejected", email, order=o["orderNumber"], orderId=o["id"], returnId=r["id"])
    if want("oldreturn"):                    # delivered long ago: DSports itself says it can no longer be returned
        email, tok = _customer("sup-oldreturn")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        s.sql(f"update orders set updated_at = now() - interval '20 days' where id='{o['id']}'")
        add("oldreturn", email, order=o["orderNumber"], orderId=o["id"])
    if want("multi"):                        # several orders: ambiguity, 'the shoes I bought', corrections
        email, tok = _customer("sup-multi")
        shoes = s.delivered_order(tok, admin, s.P_SHOE_DSC, 1)
        bat = s.buy(tok, s.P_BAT_SS, 1); s.pay(tok, bat)
        ship = _ship_to(admin, bat["id"], ("PICKED_UP", "IN_TRANSIT")); _age_shipment(ship, 0, None)
        add("multi", email, shoes=shoes["orderNumber"], shoesId=shoes["id"], bat=bat["orderNumber"], batId=bat["id"])
    if want("partial"):                      # ONE delivered order with two different products
        email, tok = _customer("sup-partial")
        o = buy_lines(tok, [(s.P_BAT_MRF, 1), (s.P_SHOE_DSC, 1)]); s.pay(tok, o); s.deliver(admin, o["id"])
        add("partial", email, order=o["orderNumber"], orderId=o["id"])
    if want("fresh"):                        # a customer with no problems and no cases
        email, _ = _customer("sup-fresh")
        add("fresh", email)
    if want("other"):                        # a second customer whose cases the first must never see
        email, tok = _customer("sup-other")
        o = s.delivered_order(tok, admin, s.P_BAT_MRF, 1)
        add("other", email, order=o["orderNumber"], orderId=o["id"])
    return world


if __name__ == "__main__":
    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    copies = int(sys.argv[sys.argv.index("--copies") + 1]) if "--copies" in sys.argv else 1
    result = json.dumps(build_many(copies, only))
    if "--out" in sys.argv:                                  # a file, written as plain UTF-8 (shell redirection adds a BOM / wrong encoding on Windows)
        with open(sys.argv[sys.argv.index("--out") + 1], "w", encoding="utf-8", newline="\n") as f:
            f.write(result)
    else:
        print(result)
