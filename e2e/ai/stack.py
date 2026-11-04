"""Real-stack helpers for AI end-to-end scenarios: create customers, orders, shipments and returns through the REAL
DSports APIs (no mocks). Admin rights are granted by SQL, exactly as e2e/api-e2e.sh does (no admin provisioning API)."""
from __future__ import annotations

import json
import os
import random
import shutil
import subprocess
import time
import urllib.error
import urllib.request

# 127.0.0.1, not "localhost": on Windows "localhost" resolves to IPv6 first and every request then pays ~2 s before falling back to IPv4 (measured: 2.07 s vs 0.03 s).
DSPORTS = os.environ.get("DSPORTS_BASE_URL", "http://127.0.0.1:8080")
AI = os.environ.get("AI_BASE_URL", "http://127.0.0.1:8090")
DB = os.environ.get("E2E_DB", "dsports")
PASSWORD = "Passw0rd!x"

P_BAT_MRF = "00000000-0000-0000-0000-000000001001"   # MRF Cricket Bat - Virgin (beginner, medium)
P_BAT_SS = "00000000-0000-0000-0000-000000001002"    # SS Cricket Bat - Prime (intermediate, light)
P_BAT_SG = "00000000-0000-0000-0000-000000001003"    # SG Cricket Bat - Test (advanced, heavy)
P_SHOE_DSC = "00000000-0000-0000-0000-000000001009"  # DSC Cricket Shoes - Spikes


def http(method: str, base: str, path: str, token: str | None = None, body=None, headers: dict | None = None,
         timeout: float = 120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            try:
                return r.status, (json.loads(raw) if raw else None)
            except ValueError:   # e.g. the Prometheus text exposition
                return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def dsports(method, path, token=None, body=None, headers=None):
    return http(method, DSPORTS, path, token, body, headers)


def ai(method, path, token=None, body=None, headers=None, timeout=180):
    return http(method, AI, path, token, body, headers, timeout)


DOCKER = (os.environ.get("DOCKER_BIN") or shutil.which("docker") or shutil.which("docker.exe")
          or "C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe")


def sql(query: str) -> str:
    out = subprocess.run([DOCKER, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", DB, "-tAc", query],
                         capture_output=True, text=True, check=True)
    return out.stdout.strip()


def restock(units: int = 500) -> None:
    """Test harnesses buy real products through the real order flow, which consumes real stock. There is no admin restock API, so (like the
    API E2E's role grant) this tops the sellable stock of the products the scenarios use back up by SQL. Sellable = available - reserved."""
    ids = ", ".join(f"'{p}'" for p in (P_BAT_MRF, P_BAT_SS, P_BAT_SG, P_SHOE_DSC))
    sql(f"update inventory set available_quantity = reserved_quantity + {int(units)}, status = 'IN_STOCK' where product_id in ({ids})")


def register(prefix: str = "ai") -> tuple[str, str]:
    email = f"{prefix}-{random.randint(0, 10**9)}@example.com"
    dsports("POST", "/api/auth/register", body={"email": email, "password": PASSWORD, "firstName": "Asha", "lastName": "Rao"})
    return email, login(email)


def login(email: str) -> str:
    status, body = dsports("POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    assert status == 200, (status, body)
    return body["accessToken"]


def make_admin(email: str) -> str:
    sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, 'ADMIN' FROM customers WHERE email='{email}' "
        f"ON CONFLICT DO NOTHING")
    return login(email)


def buy(token: str, product_id: str, qty: int = 1) -> dict:
    s, _ = dsports("POST", "/api/v1/cart/items", token, {"productId": product_id, "quantity": qty}); assert s == 200, s
    s, addr = dsports("POST", "/api/customers/me/addresses", token, {"line1": "1 MG Road", "line2": "Flat 4", "city": "Bengaluru",
                      "state": "KA", "country": "IN", "postalCode": "560001", "type": "SHIPPING"}); assert s == 201, (s, addr)
    s, co = dsports("POST", "/api/v1/checkout", token); assert s == 201, (s, co)
    cid = co["id"]
    for path, body in ((f"/api/v1/checkout/{cid}/address", {"addressId": addr["addressId"]}),
                       (f"/api/v1/checkout/{cid}/delivery", {"deliveryMethodCode": "STANDARD"}),
                       (f"/api/v1/checkout/{cid}/validate", None)):
        s, r = dsports("POST", path, token, body); assert s == 200, (path, s, r)
    s, order = dsports("POST", "/api/v1/orders", token, {"checkoutId": cid}); assert s == 201, (s, order)
    return order


def pay(token: str, order: dict) -> dict:
    s, p = dsports("POST", "/api/v1/payments", token, {"orderId": order["id"], "amount": order["grandTotal"], "currency": "INR",
                   "paymentMethod": "UPI", "paymentProvider": "MOCK"}); assert s in (200, 201), (s, p)
    s, p = dsports("POST", f"/api/v1/payments/{p['id']}/capture", token); assert s == 200, (s, p)
    return p


def deliver(admin_token: str, order_id: str) -> None:
    for _ in range(20):  # the shipment is created asynchronously through the outbox after payment capture
        ship = sql(f"select id from shipments where order_id='{order_id}'")
        if ship:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("shipment was not created")
    for status in ("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"):
        s, r = dsports("PUT", f"/api/v1/shipments/admin/{ship}/status", admin_token, {"newStatus": status}); assert s == 200, (s, r)


def delivered_order(token: str, admin_token: str, product_id: str, qty: int = 1) -> dict:
    order = buy(token, product_id, qty)
    pay(token, order)
    deliver(admin_token, order["id"])
    return order
