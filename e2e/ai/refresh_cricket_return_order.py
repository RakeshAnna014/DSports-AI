"""DEMO DATA ONLY. Keeps the browser journey M12 (return with several products in the latest order) runnable over time.

The journey needs demo.cricket@example.com to have a DELIVERED two-product order inside the 7-day return window (app.returns.window). The window is real policy, so a seeded
order simply ages out a week after seeding. This adds ONE new order through the real APIs (register is a no-op, cart, checkout, order, payment, shipment steps) when the newest order
is older than FRESH_DAYS. It never edits or deletes an existing order, never back-dates anything and does nothing while the newest order is still fresh. Run it as often as you like.

    python e2e/ai/refresh_cricket_return_order.py
"""
import sys
from datetime import datetime, timedelta, timezone

import stack as s
from seed_demo_customers import advance, buy_many, product_ids

EMAIL = "demo.cricket@example.com"
FRESH_DAYS = 4


def main() -> None:
    token = s.login(EMAIL)
    status, orders = s.dsports("GET", "/api/v1/orders", token)
    assert status == 200, (status, orders)
    newest = max((datetime.fromisoformat(o["placedAt"].replace("Z", "+00:00")) for o in orders), default=None)
    if newest is not None and datetime.now(timezone.utc) - newest < timedelta(days=FRESH_DAYS):
        print("PREP_OK: the newest demo.cricket order is still inside the return window; nothing to do")
        return
    ids = product_ids()
    s.restock()
    admin_email, _ = s.register("demoadm")
    admin = s.make_admin(admin_email)
    order = buy_many(token, [ids["MRF Cricket Bat - Virgin"], ids["MRF Cricket Gloves - Pro"]])
    s.pay(token, order)
    advance(admin, order["id"], "DELIVERED")
    print(f"PREP_OK: created delivered order {order['orderNumber']} for {EMAIL} (DEMO DATA)")


if __name__ == "__main__":
    sys.exit(main())
