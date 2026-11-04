"""Creates the users the Product & Supplier Intelligence browser journey needs (a buyer, a category manager, a customer, a support executive) through the real APIs and the same SQL role
grant the other harnesses use. Prints JSON for e2e/sourcing-ui.mjs. Nothing existing is touched; every run uses new throw-away users.

    python e2e/ai/sourcing_data.py > world.json
"""
import json
import sys

import stack as s


def grant(email: str, role: str) -> None:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")


def main() -> None:
    out = {"password": s.PASSWORD}
    for key, prefix, role in (("buyer", "uibuyer", "PROCUREMENT_BUYER"), ("manager", "uimanager", "CATEGORY_MANAGER"), ("support", "uisupport", "SUPPORT_EXECUTIVE"), ("customer", "uicustomer", None)):
        email, _ = s.register(prefix)
        if role:
            grant(email, role)
        out[key] = email
    sys.stdout.write(json.dumps(out))


if __name__ == "__main__":
    main()
