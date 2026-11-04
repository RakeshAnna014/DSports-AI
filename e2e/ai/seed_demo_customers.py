"""DEMO DATA ONLY. Creates four demo customers with purchase histories through the REAL DSports APIs (register, cart, checkout, order,
payment, shipment transitions), so personalisation and the order/return/tracking flows can be demonstrated. Idempotent: a customer who
already has orders is left alone.

    python e2e/ai/seed_demo_customers.py [--password Passw0rd!x]

| Customer                  | Profile                       | History                                                                    | State                    |
| demo.cricket@example.com  | cricket, Rs 5,000-10,000      | MRF Cricket Bat - Virgin, MRF Cricket Gloves - Pro                         | delivered                |
| demo.running@example.com  | running, Rs 3,000-8,000       | Asics Running Shoes - Daily Trainer, Puma Running Socks - Pack of 3        | out for delivery         |
| demo.football@example.com | football, Rs 4,000-12,000     | Nivia Football Boots - Dominator, Nivia Football Shin Guards - Ranger      | delivered                |
| demo.multi@example.com    | multi-sport (cricket, running, gym, football, badminton) | SS bat; Adidas running shoes; Nivia gym gloves; Nike football; Yonex racquet | delivered, delivered, delivered, delivered, processing |
"""
import json
import sys
import time

import stack as s

PASSWORD = s.PASSWORD

CUSTOMERS = [
    ("demo.cricket@example.com", "Rohan", "Cricket", [(["MRF Cricket Bat - Virgin", "MRF Cricket Gloves - Pro"], "DELIVERED")]),
    ("demo.running@example.com", "Asha", "Runner", [(["Asics Running Shoes - Daily Trainer", "Puma Running Socks - Pack of 3"], "OUT_FOR_DELIVERY")]),
    ("demo.football@example.com", "Dev", "Striker", [(["Nivia Football Boots - Dominator", "Nivia Football Shin Guards - Ranger"], "DELIVERED")]),
    ("demo.multi@example.com", "Meera", "AllRounder", [(["SS Cricket Bat - Prime"], "DELIVERED"), (["Adidas Running Shoes - Cushion Plus"], "DELIVERED"),
                                                    (["Nivia Gym Gloves - Padded"], "DELIVERED"), (["Nike Football - Premier League"], "DELIVERED"),
                                                    (["Yonex Badminton Racquet - Intermediate"], "PROCESSING")]),
]


def product_ids() -> dict[str, str]:
    out: dict[str, str] = {}
    for page in range(10):
        status, rows = s.dsports("GET", f"/api/catalog/products?page={page}&size=100")
        out.update({r["name"]: r["id"] for r in rows})
        if len(rows) < 100:
            break
    return out


def buy_many(token: str, ids: list[str]) -> dict:
    for pid in ids:
        st, _ = s.dsports("POST", "/api/v1/cart/items", token, {"productId": pid, "quantity": 1}); assert st == 200, st
    st, addr = s.dsports("POST", "/api/customers/me/addresses", token, {"line1": "12 Residency Road", "line2": "Apt 5", "city": "Bengaluru", "state": "KA",
                         "country": "IN", "postalCode": "560025", "type": "SHIPPING"}); assert st == 201, (st, addr)
    st, co = s.dsports("POST", "/api/v1/checkout", token); assert st == 201, (st, co)
    cid = co["id"]
    for path, body in ((f"/api/v1/checkout/{cid}/address", {"addressId": addr["addressId"]}), (f"/api/v1/checkout/{cid}/delivery", {"deliveryMethodCode": "STANDARD"}),
                       (f"/api/v1/checkout/{cid}/validate", None)):
        st, r = s.dsports("POST", path, token, body); assert st == 200, (path, st, r)
    st, order = s.dsports("POST", "/api/v1/orders", token, {"checkoutId": cid}); assert st == 201, (st, order)
    return order


def advance(admin: str, order_id: str, final: str) -> None:
    for _ in range(30):                     # the shipment is created asynchronously (outbox) after the payment is captured
        ship = s.sql(f"select id from shipments where order_id='{order_id}'")
        if ship:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("shipment was not created")
    if final == "PROCESSING":
        return
    steps = ["PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"]
    for status in steps[: steps.index(final) + 1]:
        st, r = s.dsports("PUT", f"/api/v1/shipments/admin/{ship}/status", admin, {"newStatus": status}); assert st == 200, (status, st, r)


def main() -> None:
    ids = product_ids()
    s.restock()
    admin_email, _ = s.register("demoadm")
    admin = s.make_admin(admin_email)
    report = []
    for email, first, last, orders in CUSTOMERS:
        st, _ = s.dsports("POST", "/api/auth/register", body={"email": email, "password": PASSWORD, "firstName": first, "lastName": last})
        token = s.login(email)
        st, existing = s.dsports("GET", "/api/v1/orders", token)
        if st == 200 and existing:
            report.append({"email": email, "orders": [o["orderNumber"] for o in existing], "created": False})
            continue
        made = []
        for names, final in orders:
            order = buy_many(token, [ids[n] for n in names])
            s.pay(token, order)
            advance(admin, order["id"], final)
            made.append({"order": order["orderNumber"], "items": names, "state": final})
        report.append({"email": email, "created": True, "orders": made})
    print(json.dumps({"password": PASSWORD, "customers": report}, indent=1))


if __name__ == "__main__":
    sys.exit(main())
