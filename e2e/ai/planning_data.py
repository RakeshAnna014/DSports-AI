"""Prepares the real stack for the Sales & Inventory Planning browser journey and prints a JSON "world" (users, product ids) on stdout. Idempotent.

  python e2e/ai/planning_data.py > world.json        creates users (admin, planner, manager, store manager, customer), makes sure the labelled demonstration network and a forecast exist
  python e2e/ai/planning_data.py --cleanup           removes the plans, imports and corrections the browser journey created (the audit log is kept)
"""
from __future__ import annotations

import json
import subprocess
import sys

import stack as s


def ai_sql(query: str) -> str:
    out = subprocess.run([s.DOCKER, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", "dsports_ai", "-tAc", query], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def grant(email: str, role: str) -> str:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")
    return s.login(email)


def cleanup() -> None:
    ai_sql("DELETE FROM pln_actual_adjustment WHERE note LIKE 'E2E%'")
    ai_sql("DELETE FROM pln_actual_adjustment WHERE note = 'two tills entered the same sale'")      # left behind when planning_e2e.py stops before its own cleanup; it would change the actual the browser journey expects
    ai_sql("DELETE FROM pln_sales_week WHERE data_source = 'IMPORTED'")
    ai_sql("DELETE FROM pln_data_through WHERE data_source = 'IMPORTED'")
    ai_sql("DELETE FROM pln_import_batch WHERE batch_id LIKE 'ui-e2e-%' OR batch_id LIKE 'e2e-%'")
    ai_sql("DELETE FROM pln_scenario WHERE name LIKE 'E2E %' OR plan_id IN (SELECT id FROM pln_plan WHERE name LIKE 'E2E %')")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E %' AND parent_plan_id IS NOT NULL")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E %'")


def main() -> int:
    if "--cleanup" in sys.argv:
        cleanup()
        return 0
    cleanup()
    emails = {}
    for key, role in (("admin", "ADMIN"), ("planner", "SALES_PLANNER"), ("manager", "CATEGORY_MANAGER"), ("store", "STORE_MANAGER"), ("customer", None)):
        email, token = s.register(f"ui{key}")
        if role:
            grant(email, role)
        emails[key] = email
    admin = s.login(emails["admin"])
    if not ai_sql("select 1 from pln_forecast_run where source = 'POC_SYNTHETIC' and is_current and status = 'SUCCEEDED'"):
        st, r = s.ai("POST", "/v1/planning/demo/seed", admin)
        assert st == 201, (st, r)
        st, r = s.ai("POST", "/v1/planning/forecast/runs", admin, {"scope": "POC_SYNTHETIC"})
        assert st == 201, (st, r)
    uid = s.sql(f"select id from customers where email='{emails['store']}'")
    st, r = s.ai("POST", "/v1/planning/scope", admin, {"user_id": uid, "location_id": "N-Z1-S1"})
    assert st == 200, (st, r)
    print(json.dumps({"password": s.PASSWORD, **emails, "bat": s.P_BAT_MRF}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
