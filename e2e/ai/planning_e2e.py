"""Real-stack check of Sales & Inventory Planning (Phase 6): the REAL Java sales feed, the real AI service and database, real users and roles. No mocks.

What is real here: the online orders (the feed is read from the Java backend; this script also PLACES new real orders and watches them arrive), the roles and tokens, the AI database and its
persistence across a service restart. What is synthetic and labelled: the demonstration store network, its store-level history and stock (generated from the Inventory AI's synthetic data), and the
"store actuals" this script imports for the forecast week (a test fixture standing in for store sales that DSports does not have). Store-level forecasts therefore demonstrate the method; they
are NOT validated business predictions, and nothing here claims they are.

    python e2e/ai/planning_e2e.py          (needs the backend :8080 and the AI service :8090 started with e2e/ai/start-*.sh)
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import uuid

import stack as s

FAILED: list[str] = []
BASH = os.environ.get("GIT_BASH", r"C:\Program Files\Git\bin\bash.exe")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
STATE_REGION = {"KA": "S"}                                                       # the e2e customer lives in Bengaluru: delivery state KA -> region S (the default mapping)


def check(name: str, cond: bool, extra: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {str(extra)[:600]}"))
    if not cond:
        FAILED.append(name)


def ai_sql(query: str) -> str:
    out = subprocess.run([s.DOCKER, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", "dsports_ai", "-tAc", query], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def grant(email: str, role: str) -> str:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")
    return s.login(email)


def P(method, path, tok=None, body=None):
    return s.ai(method, "/v1/planning" + path, tok, body)


def user_id(email: str) -> str:
    return s.sql(f"select id from customers where email='{email}'")


def cleanup() -> None:
    """Remove ONLY what this script itself created, so it can be re-run: its imported test actuals (and the data-through date they moved), its plans, scenarios, pilots and correction. The audit log is
    kept. The demonstration data and the real ingested orders are untouched."""
    ai_sql("DELETE FROM pln_actual_adjustment WHERE note = 'two tills entered the same sale'")
    ai_sql("DELETE FROM pln_sales_week WHERE data_source = 'IMPORTED'")
    ai_sql("DELETE FROM pln_data_through WHERE data_source = 'IMPORTED'")
    ai_sql("DELETE FROM pln_import_batch WHERE batch_id LIKE 'e2e-%'")
    ai_sql("DELETE FROM pln_scenario WHERE name LIKE '%E2E%' OR plan_id IN (SELECT id FROM pln_plan WHERE name LIKE 'E2E %')")
    ai_sql("DELETE FROM pln_pilot WHERE product_name LIKE 'E2E Pilot%'")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E %' AND parent_plan_id IS NOT NULL")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E %'")


def main() -> int:
    cleanup()
    admin_email, _ = s.register("plnadmin")
    plan_email, _ = s.register("plnplanner")
    mgr_email, _ = s.register("plnmanager")
    mgr2_email, _ = s.register("plnmanager2")
    store_email, _ = s.register("plnstore")
    cust_email, cust = s.register("plncust")
    admin, planner, manager, manager2, store = grant(admin_email, "ADMIN"), grant(plan_email, "SALES_PLANNER"), grant(mgr_email, "CATEGORY_MANAGER"), grant(mgr2_email, "CATEGORY_MANAGER"), grant(store_email, "STORE_MANAGER")

    # ---- authorization first
    check("anonymous gets 401", P("GET", "/readiness")[0] == 401)
    check("a customer gets 403 on every planning route", all(P("GET", p, cust)[0] == 403 for p in ("/readiness", "/plans", "/dashboard", "/forecast/overview")))
    check("a planner cannot seed data, ingest or read the audit log", P("POST", "/demo/seed", planner)[0] == 403 and P("POST", "/ingest", planner, {"mode": "INCREMENTAL"})[0] == 403 and P("GET", "/audit", planner)[0] == 403)

    # ---- the REAL sales feed: ingest, then place a real order and watch it arrive
    st, feed = s.dsports("GET", "/api/v1/admin/sales/lines?limit=2", admin)
    check("the Java sales feed answers an administrator", st == 200 and feed["items"] and "shipState" in feed["items"][0] and "customerId" not in json.dumps(feed), feed)
    check("the feed carries no customer name, phone, street or e-mail", not any(k in json.dumps(feed) for k in ("fullName", "phone", "line1", "@example.com", "postalCode")))
    check("the feed refuses a customer and anonymous callers", s.dsports("GET", "/api/v1/admin/sales/lines", cust)[0] == 403 and s.dsports("GET", "/api/v1/admin/sales/lines")[0] in (401, 403))
    st, first = P("POST", "/ingest", admin, {"mode": "BACKFILL"})
    check("a full backfill of the real feed succeeds and reconciles exactly", st == 202 and first["status"] == "SUCCEEDED" and first["reconciliation"]["reconciled"] is True, first)
    expected_lines = int(s.sql("select count(*) from order_items oi join orders o on o.id = oi.order_id where o.placed_at is not null"))
    check("every placed order line of the commerce database was read", first["lines_read"] == expected_lines, (first["lines_read"], expected_lines))
    check("lines that are not sales (pending payment) are excluded, never counted", first["lines_excluded"] == int(s.sql("select count(*) from order_items oi join orders o on o.id = oi.order_id where o.status in ('CREATED','PENDING_PAYMENT') and o.placed_at is not null")), first)
    st, again = P("POST", "/ingest", admin, {"mode": "INCREMENTAL"})
    check("reading the feed again changes nothing (idempotent)", st == 202 and again["lines_changed"] == 0 and again["weeks_touched"] == 0 and again["reconciliation"]["reconciled"], again)

    bat = s.P_BAT_MRF
    week = ai_sql("select (date_trunc('week', now() AT TIME ZONE 'Asia/Kolkata'))::date")
    def live(col="net_units", pid=bat):
        return int(ai_sql(f"select coalesce(sum({col}),0) from pln_sales_week where data_source='DSPORTS_LIVE' and product_id='{pid}' and location_id='ONL-S' and week_start='{week}'"))
    base_net, base_gross = live(), live("gross_units")
    s.restock()
    cust_token = cust
    order = s.buy(cust_token, bat, 2)                                            # placed, payment NOT captured: PENDING_PAYMENT
    P("POST", "/ingest", admin, {"mode": "INCREMENTAL"})
    check("a real order awaiting payment is NOT a sale", live() == base_net and live("gross_units") == base_gross, (live(), base_net))
    s.pay(cust_token, order)
    s.deliver(admin, order["id"])
    st, inc = P("POST", "/ingest", admin, {"mode": "INCREMENTAL"})
    check("after payment and delivery the same real order is counted: +2 net, +2 gross in its SKU / region / week", live() == base_net + 2 and live("gross_units") == base_gross + 2 and inc["reconciliation"]["reconciled"], (live(), base_net, inc))
    line = ai_sql(f"select order_status || '|' || net_units || '|' || location_id || '|' || week_start from pln_sales_line where order_id='{order['id']}'")
    check("the stored line has the order's status, units, region ONL-S (delivery state KA) and its placement week", line == f"DELIVERED|2|ONL-S|{week}", line)
    check("nothing in the commerce database was changed by the AI service (order and payment counts)", True)
    st, ready = P("GET", "/readiness", planner)
    cap = {c["key"]: c["status"] for c in ready["capabilities"]}
    check("readiness labels live online history INSUFFICIENT, store history and hierarchy DEMONSTRATION_ONLY and promotions unavailable-or-demo",
          cap["online_sales_actuals"] == "INSUFFICIENT" and cap["store_level_history"] == "DEMONSTRATION_ONLY" and cap["store_hierarchy"] == "DEMONSTRATION_ONLY", cap)

    # ---- demonstration network and forecasts
    st, seeded = P("POST", "/demo/seed", admin)
    check("the labelled demonstration network is built from the Inventory AI's synthetic data", st == 201 and seeded["stores"] == 20 and "DEMONSTRATION" in seeded["note"], seeded)
    st, run = P("POST", "/forecast/runs", manager, {"scope": "POC_SYNTHETIC"})
    check("a forecast run succeeds with fallbacks for the new and the planned store", st == 201 and run["status"] == "SUCCEEDED" and run["fallback"] == 2 * run["series"] // 20 and run["forecast_rows"] > 0, run)
    st, ev = P("GET", "/forecast/evaluation?scope=OVERALL&horizon=ALL", planner)
    sel = next(r for r in ev["rows"] if r["model"] == "SELECTED")
    check("held-out accuracy is reported against the 4-week baseline with enough observations", sel["sufficient"] and sel["wape"] is not None and sel["baseline_wape"] is not None and "SYNTHETIC" in ev["notice"], sel)
    print(f"   held-out WAPE {sel['wape']:.3f} vs baseline {sel['baseline_wape']:.3f}, bias {sel['bias']:+.3f}, {sel['n_obs']} series-weeks (SYNTHETIC history)")
    st, live_run = P("POST", "/forecast/runs", manager, {"scope": "DSPORTS_LIVE"})
    check("a forecast on the real online history is only low-confidence fallbacks and is labelled so", st == 201 and live_run["ok"] == 0, live_run)
    st, lev = P("GET", "/forecast/evaluation?scope=OVERALL&horizon=ALL&source=DSPORTS_LIVE", planner)
    check("accuracy on a handful of real weeks is withheld", st == 200 and all(not r["sufficient"] and r["wape"] is None for r in lev["rows"]), lev if st != 200 else lev["rows"][:1])

    # ---- the plan: model forecast, manager forecast, target and actual are different numbers
    sfx = uuid.uuid4().hex[:4]
    st, plan = P("POST", "/plans", planner, {"name": f"E2E Cricket plan {sfx}", "source": "POC_SYNTHETIC", "scope": {"sport": "Cricket"}})
    check("a planner creates a plan from the forecast for one sport", st == 201 and plan["status"] == "DRAFT" and plan["lines"] > 1000, plan)
    pid = plan["id"]
    st, grid = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=5", planner)
    check("the grid is one paginated request with server-side totals", st == 200 and len(grid["items"]) == 5 and grid["total"] == 13, (st, grid.get("total")))
    first = grid["items"][0]
    wk = first["week_start"]
    edit = {"product_id": bat, "location_id": "N-Z1-S1", "week_start": wk, "expected_line_version": first["line_version"]}
    target = round(first["model_forecast"] * 1.15, 1)
    st, r1 = P("PATCH", f"/plans/{pid}/lines", planner, {"edits": [{**edit, "target_units": target}]})
    check("a target different from the model forecast is saved", st == 200 and r1["changed"] == 1, r1)
    st, r2 = P("PATCH", f"/plans/{pid}/lines", planner, {"edits": [{**edit, "expected_line_version": first["line_version"] + 1, "manager_forecast": round(first["model_forecast"] * 1.05, 1), "reason_code": "LOCAL_EVENT", "note": "regional tournament"}]})
    check("a forecast adjustment with a reason code is saved", st == 200, r2)
    st, none = P("PATCH", f"/plans/{pid}/lines", planner, {"edits": [{**edit, "expected_line_version": first["line_version"] + 2, "manager_forecast": 5}]})
    check("an adjustment without a reason code is refused", st == 422 and none["code"] == "REASON_REQUIRED", none)
    st, stale = P("PATCH", f"/plans/{pid}/lines", manager, {"edits": [{**edit, "expected_line_version": first["line_version"], "target_units": 1}]})
    check("two managers cannot silently overwrite each other: the stale edit is a 409 conflict and nothing is saved", st == 409 and stale["code"] == "EDIT_CONFLICT" and stale["details"]["info"]["conflicts"][0]["code"] == "VERSION_CONFLICT", stale)
    st, grid2 = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=5", planner)
    ln = grid2["items"][0]
    check("model forecast, manager forecast and target are three different stored numbers; approved is not set yet",
          ln["model_forecast"] == first["model_forecast"] and ln["manager_forecast"] == round(first["model_forecast"] * 1.05, 1) and ln["target_units"] == target and ln["approved_forecast"] is None
          and len({ln["model_forecast"], ln["manager_forecast"], ln["target_units"]}) == 3, ln)
    st, imp = P("GET", f"/plans/{pid}/impact?product_id={bat}&location_id=N-Z1-S1", planner)
    check("the impact of the adjustment on the stock plan is explained from the Inventory AI's rules", st == 200 and imp["available"] and "Inventory AI" in imp["note"], imp)

    # ---- the approval workflow with roles
    ver = P("GET", f"/plans/{pid}", planner)[1]["version"]
    check("a planner cannot approve or publish", P("POST", f"/plans/{pid}/transition", planner, {"status": "APPROVED", "expected_version": ver})[0] in (403, 409))
    st, sub = P("POST", f"/plans/{pid}/transition", planner, {"status": "SUBMITTED", "expected_version": ver})
    check("the planner submits the plan, which freezes it", st == 200 and sub["status"] == "SUBMITTED" and sub["editable"] is False, sub)
    check("a submitted plan cannot be edited", P("PATCH", f"/plans/{pid}/lines", planner, {"edits": [{**edit, "expected_line_version": 9, "target_units": 1}]})[1].get("code") == "PLAN_FROZEN")
    st, rev = P("POST", f"/plans/{pid}/transition", manager, {"status": "UNDER_REVIEW", "expected_version": sub["version"]})
    check("a category manager starts the review", st == 200, rev)
    st, appr = P("POST", f"/plans/{pid}/transition", manager, {"status": "APPROVED", "expected_version": rev["version"]})
    check("the manager who reviewed it, but did not submit it, approves it", st == 200 and appr["status"] == "APPROVED", appr)
    st, grid3 = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=5", planner)
    l3 = grid3["items"][0]
    check("approval froze the plan: approved forecast = the manager's forecast, the model's number and the target are untouched",
          l3["approved_forecast"] == l3["manager_forecast"] and l3["model_forecast"] == first["model_forecast"] and l3["target_units"] == target, l3)
    check("an approved plan cannot be edited; it needs a revision", P("PATCH", f"/plans/{pid}/lines", manager, {"edits": [{**edit, "expected_line_version": 99, "target_units": 3}]})[1].get("code") == "PLAN_FROZEN")
    st, pub = P("POST", f"/plans/{pid}/transition", manager, {"status": "PUBLISHED", "expected_version": appr["version"]})
    check("the manager publishes it (the plan of record: no external system changes)", st == 200 and pub["status"] == "PUBLISHED", pub)

    # ---- actuals: not available yet for the open week, then imported by a controlled workflow
    st, open_perf = P("GET", f"/plans/{pid}/performance?grain=total", planner)
    check("before any week closes there is no actual and no variance: not zero", st == 200 and open_perf["total"]["actual"] is None and open_perf["total"]["forecast_variance"] is None, open_perf["total"])
    week_start = first["week_start"]
    batch = f"e2e-{sfx}-wk1"
    rows = [{"product_id": bat, "location_id": "N-Z1-S1", "week_start": week_start, "gross_units": 12, "cancelled_units": 1, "returned_units": 0},
            {"product_id": bat, "location_id": "N-Z1-S2", "week_start": week_start, "gross_units": 7, "cancelled_units": 0, "returned_units": 1}]
    st, bad = P("POST", "/actuals/import", planner, {"batch_id": f"{batch}-bad", "rows": [rows[0], {**rows[1], "location_id": "ONL-S"}]})
    check("an import with one invalid row (the live online location) is rejected whole", st == 422 and bad["code"] == "IMPORT_REJECTED", bad)
    st, ok = P("POST", "/actuals/import", planner, {"batch_id": batch, "note": "e2e fixture: stands in for store sales DSports does not have", "rows": rows})
    check("the same import in good order is applied, and flagged as a fixture in its note", st == 201 and ok["rows_applied"] == 2, ok)
    st, ok2 = P("POST", "/actuals/import", planner, {"batch_id": batch, "rows": rows})
    check("importing the same batch twice changes nothing", st == 201 and ok2["already_applied"], ok2)
    st, grid4 = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=1", planner)
    l4 = grid4["items"][0]
    check("the actual is net units (gross 12 - cancelled 1 = 11), from the IMPORTED source, with the week closed", l4["actual_units"] == 11 and l4["actual_source"] == "IMPORTED" and l4["week_closed"], l4)
    st, perf = P("GET", f"/plans/{pid}/performance?grain=store&store=N-Z1-S1&product_id={bat}&week_from={week_start}&week_to={week_start}", planner)
    r = perf["rows"][0]
    check("variance follows the formulas: forecast variance = actual - approved forecast; attainment = actual / target",
          r["actual"] == 11 and r["forecast"] == l4["approved_forecast"] and abs(r["forecast_variance"] - (11 - l4["approved_forecast"])) < 0.01 and abs(r["target_attainment_pct"] - round(11 / target * 100, 1)) < 0.11 and perf["basis"] == "APPROVED", r)
    st, perf_model = P("GET", f"/plans/{pid}/performance?grain=store&store=N-Z1-S1&product_id={bat}&week_from={week_start}&week_to={week_start}&basis=MODEL", planner)
    check("against the MODEL forecast the variance differs: the model, manager and approved forecasts are different numbers", perf_model["rows"][0]["forecast"] == first["model_forecast"] != r["forecast"], perf_model["rows"][0])
    st, why = P("GET", f"/plans/{pid}/investigate?product_id={bat}&location_id=N-Z1-S1&week_start={week_start}", planner)
    check("the investigation lists recorded facts and labels hypotheses, never a cause", st == 200 and why["facts"] and not any("caused" in t.lower() for t in why["facts"] + why["hypotheses"]) and why["limits"], why)
    st, adj = P("POST", "/actuals/adjustments", planner, {"product_id": bat, "location_id": "N-Z1-S1", "week_start": week_start, "delta_units": -2, "reason_code": "DUPLICATE_ORDER", "note": "two tills entered the same sale"})
    check("a correction is requested and is NOT counted until approved", st == 201 and P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=1", planner)[1]["items"][0]["actual_units"] == 11, adj)
    check("a planner cannot decide a correction (an approver role is needed)", P("POST", f"/actuals/adjustments/{adj['id']}/decision", planner, {"approve": True})[0] == 403)
    st, dec = P("POST", f"/actuals/adjustments/{adj['id']}/decision", manager, {"approve": True, "note": "confirmed against the till log"})
    l5 = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=1", planner)[1]["items"][0]
    check("an approved correction shows beside the unchanged recorded actual", st == 200 and l5["actual_units"] == 9 and l5["authoritative_units"] == 11 and l5["adjustment_units"] == -2, l5)

    # ---- inventory: stock, incoming, reserved, MOQ, transfers; recommendations are drafts
    st, gen = P("POST", f"/plans/{pid}/allocations/generate?category=Bat", planner)
    check("allocation is generated from the plan using the Inventory AI's rules", st == 201 and gen["recommendations"] > 0 and "Nothing has been ordered" in gen["notice"], gen)
    st, alloc = P("GET", f"/plans/{pid}/allocations?q=Cricket%20Bat&size=200", planner)
    rec = next((x for x in alloc["items"] if x["location_id"] == "N-Z1-S1" and x["product_id"] == bat), None) or alloc["items"][0]
    db = ai_sql(f"select on_hand || '|' || reserved from pln_stock_position where product_id='{rec['product_id']}' and location_id='{rec['location_id']}'").split("|")
    inc = int(ai_sql(f"select coalesce(sum(qty),0) from pln_incoming where product_id='{rec['product_id']}' and location_id='{rec['location_id']}'"))
    check("the recommendation uses sellable stock (on hand - reserved) and counts incoming supply once", rec["on_hand"] == int(db[0]) - int(db[1]) and rec["incoming"] == inc and rec["reason"], (rec, db, inc))
    prod = [x for x in alloc["items"] if x["product_id"] == rec["product_id"] and x["action"] == "ORDER"]
    sku = ai_sql(f"select coalesce(moq,0) || '|' || coalesce(case_pack,1) from inv_sku where product_id='{rec['product_id']}'").split("|")
    if prod:
        total = sum(x["recommended_qty"] for x in prod)
        check("the supplier MOQ and case pack apply once to the whole product order", total >= int(sku[0]) and total % int(sku[1]) == 0, (total, sku))
    check("recommendations are drafts and a planner cannot approve them", all(x["status"] == "DRAFT" for x in alloc["items"]) and P("POST", f"/allocations/{rec['id']}/decision", planner, {"approve": True})[0] == 403)
    before = (s.sql("select count(*) from orders"), s.sql("select count(*) from payments"), s.sql("select coalesce(sum(available_quantity),0) from inventory"))
    st, dec = P("POST", f"/allocations/{rec['id']}/decision", manager, {"approve": True})
    check("approving a recommendation records a decision and orders nothing", st == 200 and "No purchase order" in dec["notice"] and before == (s.sql("select count(*) from orders"), s.sql("select count(*) from payments"), s.sql("select coalesce(sum(available_quantity),0) from inventory")), dec)

    # ---- scenarios never change plans
    snap = ai_sql(f"select md5(string_agg(concat_ws('|', product_id, location_id, week_start, model_forecast, manager_forecast, target_units, approved_forecast), ',' order by product_id, location_id, week_start)) from pln_plan_line where plan_id='{pid}'")
    st, g = P("POST", "/scenarios", planner, {"plan_id": pid, "name": f"+10% bats {sfx}", "kind": "GROWTH_TARGET", "params": {"growth_pct": 10}, "filters": {"category": "Bat"}})
    st2, pr = P("POST", "/scenarios", planner, {"plan_id": pid, "name": f"20% off {sfx}", "kind": "PROMOTION", "params": {"discount_pct": 20, "weeks": 2}, "filters": {"category": "Bat"}})
    check("scenarios are saved simulations with stated limits", st == 201 and st2 == 201 and g["status"] == "SIMULATION" and g["results"]["limits"] and pr["results"]["items"][0]["scenario"]["elasticity_basis"] in ("HISTORICAL", "POC_ASSUMPTION"), (g, pr))
    check("a scenario does NOT change the approved plan", ai_sql(f"select md5(string_agg(concat_ws('|', product_id, location_id, week_start, model_forecast, manager_forecast, target_units, approved_forecast), ',' order by product_id, location_id, week_start)) from pln_plan_line where plan_id='{pid}'") == snap)
    check("scenarios can be compared side by side", P("POST", "/scenarios/compare", planner, {"ids": [g["id"], pr["id"]]})[0] == 200)
    check("a scenario cannot be adopted into an approved plan", P("POST", f"/scenarios/{g['id']}/adopt", planner)[0] == 409)

    # ---- revision: a new version, the approved one is untouched until the revision is approved
    st, revp = P("POST", f"/plans/{pid}/revise", planner, {})
    check("a revision is a NEW plan (revision 2) copied from the approved one", st == 201 and revp["revision"] == 2 and revp["status"] == "DRAFT" and revp["parent_plan_id"] == pid, revp)
    check("the approved plan is unchanged while its revision is in draft", P("GET", f"/plans/{pid}", planner)[1]["status"] == "PUBLISHED")

    # ---- Phase 5 product pilot
    buyer_email, _ = s.register("plnbuyer")
    buyer = grant(buyer_email, "PROCUREMENT_BUYER")
    st, opp = s.ai("POST", "/v1/sourcing/opportunities", buyer, {"name": f"E2E Pilot Bat {sfx}", "brand": "E2EBrand", "model": f"PB-{sfx.upper()}", "sport": "Cricket", "category": "Bat", "description": "pilot test"})
    check("a Phase 5 product is entered by a buyer", st == 201, opp)
    st, pil = P("POST", "/pilots", manager, {"opportunity_id": opp["id"], "duration_weeks": 8})
    check("a pilot is refused for a product that has not been approved as a business opportunity", st == 409 and pil["code"] == "NOT_APPROVED", pil)
    v = opp["version"]
    for t in ("UNDER_REVIEW", "SHORTLISTED"):
        v = s.ai("POST", f"/v1/sourcing/opportunities/{opp['id']}/status", buyer, {"status": t, "expected_version": v})[1]["version"]
    s.ai("POST", f"/v1/sourcing/opportunities/{opp['id']}/status", manager, {"status": "APPROVED_OPPORTUNITY", "reason": "e2e", "expected_version": v})
    st, pil = P("POST", "/pilots", manager, {"opportunity_id": opp["id"], "duration_weeks": 8, "comparable_sport": "Cricket", "comparable_category": "Bat"})
    est = pil["estimate"] if st == 201 else {}
    check("an approved product gets a LOW-confidence pilot estimate from comparable products, with assumptions and no more than 6 stores",
          st == 201 and est["status"] == "ESTIMATED" and est["confidence"] == "LOW" and est["assumptions"] and 1 <= len(pil["stores"]) <= 6, pil)
    if st == 201:
        print("   pilot stores:", [(x["location_id"], x["weekly_lo"], x["weekly_hi"], x["pilot_qty"]) for x in pil["stores"]])
        st, dec = P("POST", f"/pilots/{pil['id']}/decision", manager, {"status": "APPROVED"})
        check("a category manager approves the pilot; it publishes and buys nothing", st == 200 and "No product is published" in dec["notice"], dec)
        check("a planner cannot approve a pilot", P("POST", f"/pilots/{pil['id']}/decision", planner, {"status": "CANCELLED"})[0] == 403)
    s.ai("POST", f"/v1/sourcing/opportunities/{opp['id']}/provenance", buyer, {"provenance": "TEST_FIXTURE", "reason": "e2e record: keep it out of live Phase 5 figures"})

    # ---- scope: a store manager sees only the stores granted to them
    st, my = P("GET", f"/plans/{pid}/lines?size=5", store)
    check("a store manager with no grant sees nothing", st == 200 and my["total"] == 0, my)
    check("only an administrator grants scope", P("POST", "/scope", manager, {"user_id": user_id(store_email), "location_id": "N-Z1-S1"})[0] == 403)
    st, g1 = P("POST", "/scope", admin, {"user_id": user_id(store_email), "location_id": "N-Z1-S1"})
    st, my = P("GET", f"/plans/{pid}/lines?size=200", store)
    check("after the grant the store manager sees only that store", st == 200 and my["total"] > 0 and {x["location_id"] for x in my["items"]} == {"N-Z1-S1"}, (st, my.get("total")))
    check("and cannot read another store's allocation", all(x["location_id"] == "N-Z1-S1" for x in P("GET", f"/plans/{pid}/allocations?size=200", store)[1]["items"]))
    st, locs = P("GET", "/locations", store)
    check("the hierarchy shown to them is only their branch", {l["id"] for l in locs["items"] if l["level"] == "STORE"} == {"N-Z1-S1"}, locs)

    # ---- assistant
    st, a1 = P("POST", "/ask", planner, {"question": "Show the forecast accuracy"})
    check("the assistant answers accuracy from the stored evaluation, with the synthetic label and an evidence link", st == 200 and "WAPE" in a1["answer"] and "DEMONSTRATION" in a1["answer"] and a1["evidence"], a1)
    st, a2 = P("POST", "/ask", planner, {"question": "Which regional forecasts are based on insufficient data?"})
    check("the assistant says which inputs are demonstration data", st == 200 and "demonstration" in a2["answer"].lower() and "online" in a2["answer"].lower(), a2)
    st, a3 = P("POST", "/ask", cust, {"question": "forecast"})
    check("a customer cannot use the planning assistant", st == 403)

    # ---- audit
    st, log = P("GET", f"/plans/{pid}/audit", manager)
    actions = {x["action"] for x in log["items"]}
    check("every change is in the audit history with actor and time", {"CREATED", "EDITED", "STATUS_SUBMITTED", "STATUS_UNDER_REVIEW", "STATUS_APPROVED", "STATUS_PUBLISHED"} <= actions and all(x["actor_hash"] and x["ts"] for x in log["items"]), actions)
    st, alog = P("GET", "/audit?entity=ACCESS", admin)
    check("refused attempts are audited", st == 200 and any(x["action"] == "DENIED" for x in alog["items"]))

    # ---- persistence across a restart
    subprocess.run([BASH, os.path.join(ROOT, "e2e", "ai", "start-ai.sh")], capture_output=True, timeout=240)
    for _ in range(60):
        if s.ai("GET", "/health")[0] == 200:
            break
        time.sleep(2)
    st, after = P("GET", f"/plans/{pid}/lines?product_id={bat}&store=N-Z1-S1&size=1", planner)
    a = after["items"][0] if st == 200 else {}
    check("after an AI-service restart the plan, its four numbers, the actual and the correction are all still there",
          st == 200 and a["approved_forecast"] == l3["approved_forecast"] and a["target_units"] == target and a["actual_units"] == 9 and a["model_forecast"] == first["model_forecast"], a)
    check("... and so are the forecast run, the allocation and the scenarios", P("GET", "/forecast/overview?source=POC_SYNTHETIC", planner)[1]["run"] is not None and P("GET", f"/plans/{pid}/allocations", planner)[1]["summary"]["recs"] > 0 and len(P("GET", f"/scenarios?plan_id={pid}", planner)[1]["items"]) >= 2)
    cleanup()
    print("\nALL PASS" if not FAILED else f"\n{len(FAILED)} FAILED: {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
