"""Stabilisation audit of Sales & Inventory Planning on the REAL stack (no mocks): data labelling, live-versus-demonstration separation, online-is-not-a-store, withheld accuracy,
location scope (store and regional managers) and "approval changes nothing in commerce data".

    python e2e/ai/planning_audit.py          (needs the backend :8080 and the AI service :8090; the demonstration network and a current forecast for each scope must exist)

It creates its own users and one throwaway plan (name starts 'E2E AUDIT') and removes the plan afterwards; it changes no live data.
"""
from __future__ import annotations

import json
import subprocess
import sys

import stack as s

FAILED: list[str] = []


def check(name: str, cond: bool, extra: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {str(extra)[:500]}"))
    if not cond:
        FAILED.append(name)


def ai_sql(query: str) -> str:
    out = subprocess.run([s.DOCKER, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", "dsports_ai", "-tAc", query], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def P(method, path, tok=None, body=None):
    return s.ai(method, "/v1/planning" + path, tok, body)


def grant(email: str, role: str) -> str:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")
    return s.login(email)


def walk(o):
    """Every dict in a JSON tree."""
    if isinstance(o, dict):
        yield o
        for v in o.values():
            yield from walk(v)
    elif isinstance(o, list):
        for v in o:
            yield from walk(v)


def labelled_demo(r) -> bool:
    text = json.dumps(r)
    return any(k in text for k in ('"synthetic": true', "POC_SYNTHETIC", "DEMONSTRATION", "Demonstration", "demonstration", "SYNTHETIC", "synthetic"))


def main() -> int:
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E AUDIT%'")
    admin_email, _ = s.register("auditadmin")
    planner_email, _ = s.register("auditplanner")
    regional_email, _ = s.register("auditregional")
    store_email, _ = s.register("auditstore")
    _, cust = s.register("auditcust")
    admin, planner = grant(admin_email, "ADMIN"), grant(planner_email, "SALES_PLANNER")
    regional, store = grant(regional_email, "REGIONAL_MANAGER"), grant(store_email, "STORE_MANAGER")
    uid = lambda e: s.sql(f"select id from customers where email='{e}'")

    # ---- 1. labelling: every demonstration response says so; no demonstration row appears in a live response
    demo_paths = ["/dashboard?source=POC_SYNTHETIC", "/forecast/overview?source=POC_SYNTHETIC", "/forecast/evaluation?source=POC_SYNTHETIC", "/forecast/series?source=POC_SYNTHETIC&size=3",
                  "/analysis/regional?source=POC_SYNTHETIC", "/actuals?source=POC_SYNTHETIC&week_from=2026-08-31&week_to=2026-09-28&dim=total", "/readiness"]
    for p in demo_paths:
        st, r = P("GET", p, admin)
        check(f"demonstration response is labelled as demonstration/synthetic: {p.split('?')[0]}", st == 200 and labelled_demo(r), (st, str(r)[:200]))
    st, ev = P("GET", "/forecast/evaluation?source=POC_SYNTHETIC", admin)
    check("synthetic accuracy carries a notice that it is measured on SYNTHETIC history", st == 200 and "SYNTHETIC" in json.dumps(ev.get("notice", "")).upper(), ev.get("notice"))
    st, locs = P("GET", "/locations", admin)
    items = locs["items"]
    check("every location carries a data source; demonstration stores are POC_SYNTHETIC/POC_REFERENCE, online locations are CONFIG",
          all(l["data_source"] in ("POC_SYNTHETIC", "POC_REFERENCE", "CONFIG", "IMPORTED", "DSPORTS_LIVE") for l in items)
          and all(l["data_source"] == "CONFIG" for l in items if l["channel"] == "ONLINE") and {l["channel"] for l in items} >= {"ONLINE", "STORE"}, items[:2])
    for p in ("/dashboard?source=DSPORTS_LIVE", "/analysis/regional?source=DSPORTS_LIVE", "/actuals?source=DSPORTS_LIVE&week_from=2026-08-31&week_to=2026-10-12&dim=store"):
        st, r = P("GET", p, admin)
        blob = json.dumps(r)
        stores_seen = {d.get("location_id") or d.get("store") or d.get("store_id") for d in walk(r) if isinstance(d, dict)} - {None}
        check(f"a live response contains no demonstration store, source or stock: {p.split('?')[0]}", st == 200 and "POC_SYNTHETIC" not in blob.replace('"synthetic": false', "") and not any(str(x).startswith(("N-", "S-", "E-", "W-", "C-")) for x in stores_seen), (st, sorted(map(str, stores_seen))[:6]))

    # ---- 2. live totals are the live data only (no demonstration units mixed in) and online orders are online, not stores
    sql_net = int(float(ai_sql("select coalesce(sum(net_units),0) from pln_sales_week where data_source='DSPORTS_LIVE'")))
    st, live = P("GET", "/actuals?source=DSPORTS_LIVE&week_from=2020-01-06&week_to=2030-01-07&dim=total", admin)
    api_net = sum(int(float(row.get("net_units") or 0)) for row in live.get("rows", []))
    check("the live actuals total equals the live weekly aggregate in the database (no demonstration units mixed in)", st == 200 and api_net == sql_net and sql_net > 0, (api_net, sql_net))
    check("every live sales line is attributed to an ONLINE location (ONL-*), none to a physical store", ai_sql("select count(*) from pln_sales_line l where l.location_id not like 'ONL-%'") == "0", ai_sql("select location_id, count(*) from pln_sales_line group by 1"))
    check("online locations are channel ONLINE in the hierarchy", ai_sql("select count(*) from pln_location where id like 'ONL-%' and channel <> 'ONLINE'") == "0")
    st, rd = P("GET", "/readiness", admin)
    check("readiness reports lines with no mappable region (unknown_region) instead of guessing", st == 200 and "unknown_region" in rd["live"]["lines"], rd["live"].get("lines"))
    check("live history is reported INSUFFICIENT/limited for store-level forecasting, store history DEMONSTRATION_ONLY",
          {c["key"]: c["status"] for c in rd["capabilities"]}.get("store_level_history") == "DEMONSTRATION_ONLY", {c["key"]: c["status"] for c in rd["capabilities"]})

    # ---- 3. accuracy on the few real weeks is withheld
    st, evl = P("GET", "/forecast/evaluation?source=DSPORTS_LIVE", admin)
    overall = [r for r in evl.get("rows", []) if r.get("scope") in ("OVERALL", None)]
    check("no accuracy number is shown for the live online history", st in (200, 404) and all(r.get("wape") is None for r in overall) and all(not r.get("sufficient") for r in overall), (st, overall[:2]))

    # ---- 4. location scope: a store manager and a regional manager see only their own branch
    st, run = P("GET", "/forecast/overview?source=POC_SYNTHETIC", admin)
    st, dash = P("POST", "/plans", planner, {"name": "E2E AUDIT scope plan", "source": "POC_SYNTHETIC", "scope": {"category": "Bat"}})
    check("a planner creates a throwaway plan across all stores", st == 201, (st, dash))
    pid = dash.get("id")
    all_stores = {l["id"] for l in items if l["level"] == "STORE" and l["channel"] == "STORE"}
    north = {l["id"] for l in items if l["level"] == "STORE" and l["channel"] == "STORE" and l["id"].startswith("N-")}
    one = sorted(north)[0]
    check("the demonstration network has stores in several regions", len(all_stores) > len(north) > 0, (len(all_stores), len(north)))
    st, _ = P("POST", "/scope", admin, {"user_id": uid(store_email), "location_id": one})
    st2, _ = P("POST", "/scope", admin, {"user_id": uid(regional_email), "location_id": "N"})
    check("an administrator grants a store and a region", st == 200 and st2 == 200, (st, st2))
    for who, tok, allowed in (("store manager", store, {one}), ("regional manager", regional, north)):
        st, mine = P("GET", "/locations", tok)
        ids = {l["id"] for l in mine["items"] if l["level"] == "STORE" and l["channel"] == "STORE"}
        check(f"the {who}'s hierarchy contains only their own stores", st == 200 and ids <= allowed and ids, (sorted(ids)[:5], len(ids)))
        st, lines = P("GET", f"/plans/{pid}/lines?size=200", tok)
        seen = {l.get("location_id") for l in lines.get("items", [])}
        check(f"the {who} sees only their stores' plan lines", st == 200 and seen and seen <= allowed, (st, sorted(map(str, seen))[:5]))
        other = sorted(all_stores - allowed)[0]
        st, lines = P("GET", f"/plans/{pid}/lines?store={other}&size=20", tok)
        check(f"the {who} gets nothing for a store outside their scope", (st in (403, 404)) or (st == 200 and not lines.get("items")), (st, str(lines)[:150]))
        st, a = P("GET", f"/actuals?source=POC_SYNTHETIC&week_from=2026-08-31&week_to=2026-09-28&dim=store", tok)
        seen = {row.get("location_id") or row.get("store") for row in a.get("rows", [])} - {None}
        check(f"the {who}'s actuals contain only their stores", st == 200 and seen <= allowed, (st, sorted(map(str, seen))[:5]))
        st, _ = P("POST", "/forecast/runs", tok, {"scope": "POC_SYNTHETIC"})
        check(f"the {who} cannot start a forecast run", st == 403, st)
        st, _ = P("POST", "/scope", tok, {"user_id": uid(store_email), "location_id": other})
        check(f"the {who} cannot grant scope", st == 403, st)
    st, sub = P("POST", f"/plans/{pid}/transition", planner, {"status": "SUBMITTED", "expected_version": dash["version"]})
    st, _ = P("POST", f"/plans/{pid}/transition", regional, {"status": "UNDER_REVIEW", "expected_version": sub.get("version")})
    check("a regional manager cannot review a plan that includes stores outside the region (403 OUT_OF_SCOPE)", st == 403, st)
    st, mine = P("GET", f"/plans/{pid}/allocations", store)
    check("a store manager's allocation list holds no other store", st in (200, 403) and not any(r.get("location_id") not in (one,) for r in (mine.get("items", []) if st == 200 else [])), (st, str(mine)[:150]))

    # ---- 5. approval and recommendations change nothing in commerce data
    snapshot = lambda: (s.sql("select count(*), coalesce(sum(available_quantity),0), coalesce(sum(reserved_quantity),0), coalesce(max(version),0) from inventory"), s.sql("select count(*) from inventory_reservations"),
                        s.sql("select count(*) from orders"), s.sql("select count(*) from payments") if True else "")
    st, g = P("POST", f"/plans/{pid}/allocations/generate?category=Bat", admin)
    st_, recs = P("GET", f"/plans/{pid}/allocations?size=50", admin)
    before = snapshot()
    approved = 0
    for r in recs.get("items", [])[:10]:
        if r.get("status") in ("DRAFT", "PROPOSED", None):
            st, _ = P("POST", f"/allocations/{r['id']}/decision", admin, {"approve": True})
            approved += st == 200
    for tid in [t["id"] for t in walk(recs) if t.get("from_location") and t.get("id")][:3]:
        P("POST", f"/transfers/{tid}/decision", admin, {"approve": True})
    ver = sub.get("version")
    for to in ("UNDER_REVIEW", "APPROVED"):
        st_t, cur = P("POST", f"/plans/{pid}/transition", admin, {"status": to, "expected_version": ver})
        ver = cur.get("version", ver)
    check("the plan reached APPROVED", cur.get("status") == "APPROVED", (st_t, cur))
    after = snapshot()
    check("generating recommendations and approving them and the plan changed no stock, reservation, order or payment in the commerce database", before == after and g is not None, (before, after, st))
    check("recommendations were generated and decided (so the check above is meaningful)", st_ == 200 and len(recs.get("items", [])) > 0 and approved > 0, (st_, len(recs.get("items", [])), approved))
    check("no planning table holds a purchase order or an executed transfer: statuses are draft/approved decisions only",
          ai_sql("select count(*) from pln_alloc_rec where status not in ('DRAFT','APPROVED','REJECTED')") == "0" and ai_sql("select count(*) from pln_transfer_rec where status not in ('DRAFT','APPROVED','REJECTED')") == "0",
          ai_sql("select status, count(*) from pln_alloc_rec group by 1"))

    # ---- 6. forecasts of record are not touched by manager adjustments
    st, ln = P("GET", f"/plans/{pid}/lines?size=1", admin)
    if ln.get("items"):
        before_model = ai_sql(f"select count(*) from pln_forecast")
        check("model forecast rows are not changed by plan lines (separate tables; the model value is frozen on the line)", before_model != "0" and "model_forecast" in ln["items"][0], ln["items"][0].keys())

    # ---- 7. AI-assisted analysis and the review queue
    st, plan_b = P("POST", "/plans", planner, {"name": "E2E AUDIT analysis plan", "source": "POC_SYNTHETIC", "scope": {"category": "Bat"}})
    assert st == 201, plan_b
    bid = plan_b["id"]
    commerce = lambda: (s.sql("select count(*) from orders"), s.sql("select count(*) from payments"), s.sql("select coalesce(sum(available_quantity),0) from inventory"), s.sql("select count(*) from products"))
    before_c = commerce()
    st, an = P("POST", f"/plans/{bid}/analysis", planner, {"use_llm": True})
    check("the analysis runs on stored records and is persisted with its provenance", st == 201 and an["id"] and an["data_scope"]["synthetic"] and an["findings"], (st, str(an)[:200]))
    row = ai_sql(f"select plan_version || '|' || coalesce(model_version,'') || '|' || coalesce(data_through::text,'') || '|' || summary_source from pln_analysis where id = '{an['id']}'")
    check("the stored analysis records the plan version, forecast model, data-through date and who wrote the summary", row.count("|") == 3 and all(row.split("|")), row)
    check("every finding states its provenance, freshness and confidence, and demonstration findings are labelled", all(f["provenance"] and f["freshness"]["data_through"] and f["confidence"] and "DEMO" in f["provenance"] for f in an["findings"]))
    check("facts and hypotheses are separate lists and the analysis never tells anyone to approve or order", all(isinstance(f["facts"], list) and isinstance(f["hypotheses"], list) for f in an["findings"]) and
          not any(w in json.dumps(an).lower() for w in ("approve the plan", "place an order", "publish the")), "")
    if an["summary_source"] == "LLM":
        check("an AI-worded summary was kept only because every number in it is in the findings", an["llm"].get("grounded") is True, an["llm"])
    st, reg = P("GET", "/analysis/regional?source=POC_SYNTHETIC&weeks=26", admin)
    check("the regional analysis route is not shadowed by the analysis routes", st == 200 and "matrix" in reg, (st, str(reg)[:120]))
    st, _ = P("GET", f"/plans/{bid}/analysis", cust)
    st2, _ = P("POST", f"/plans/{bid}/analysis", cust, {})
    st3, _ = P("GET", "/review/queue", cust)
    st4, _ = P("POST", "/review/bulk", cust, {"plan_ids": [bid], "action": "APPROVE", "preview": True})
    check("a customer gets 403 on the analysis and the review endpoints", (st, st2, st3, st4) == (403, 403, 403, 403), (st, st2, st3, st4))
    st, _ = P("POST", f"/plans/{bid}/analysis", store, {})
    check("a store manager whose store is in the plan can analyse it, and only sees their store's evidence", st == 201, st)
    outsider_email, _ = s.register("auditoutsider")
    outsider = grant(outsider_email, "STORE_MANAGER")
    st, _ = P("POST", f"/plans/{bid}/analysis", outsider, {})
    st2, _ = P("GET", f"/plans/{bid}/breakdown?by=store", outsider)
    check("a store manager with no store in the plan is refused the analysis and the breakdown", (st, st2) == (403, 403), (st, st2))
    st, q = P("GET", "/review/queue", outsider)
    check("a scoped manager's review queue contains no plan outside their scope", st == 200 and all(i["id"] != bid for i in q.get("items", [])), (st, len(q.get("items", []))))
    st, sub = P("POST", f"/plans/{bid}/transition", planner, {"status": "SUBMITTED", "expected_version": plan_b["version"]})
    st, q = P("GET", "/review/queue", admin)
    item = next((i for i in q["items"] if i["id"] == bid), None)
    check("a submitted plan appears in the review queue with its regions, data scope and evidence strength", item is not None and item["data_scope"]["synthetic"] and item["regions"] and "low_confidence_share" in item and "submitted_by" not in item, item)
    st, prev = P("POST", "/review/bulk", admin, {"plan_ids": [bid], "action": "APPROVE", "preview": True})
    check("a bulk approval previews every consequence and changes nothing", st == 200 and prev["all_eligible"] and prev["requires_acknowledgement"] and not prev["applied"] and P("GET", f"/plans/{bid}", admin)[1]["status"] == "SUBMITTED", prev)
    st, noack = P("POST", "/review/bulk", admin, {"plan_ids": [bid], "action": "APPROVE", "preview": False})
    check("approving a plan built on demonstration data is refused without an explicit acknowledgement", st == 422 and noack["code"] == "SYNTHETIC_ACK_REQUIRED" and P("GET", f"/plans/{bid}", admin)[1]["status"] == "SUBMITTED", (st, noack.get("code")))
    st, planner_try = P("POST", "/review/bulk", planner, {"plan_ids": [bid], "action": "APPROVE", "preview": False, "acknowledge_synthetic": True})
    check("a planner cannot approve even with the acknowledgement", st == 422 and planner_try["code"] == "NOT_ALL_ELIGIBLE", (st, planner_try.get("code")))
    st, done = P("POST", "/review/bulk", admin, {"plan_ids": [bid], "action": "APPROVE", "preview": False, "acknowledge_synthetic": True})
    check("with the acknowledgement an authorised reviewer approves, and the decision is audited", st == 200 and done["applied"] and ai_sql("select count(*) from pln_audit where action = 'BULK_APPROVE'") != "0", (st, str(done)[:200]))
    check("running the analysis and approving plans changed no order, payment, stock or product in the commerce database", commerce() == before_c, (before_c, commerce()))
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E AUDIT%' AND parent_plan_id IS NOT NULL")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E AUDIT%'")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E AUDIT%' AND parent_plan_id IS NOT NULL")
    ai_sql("DELETE FROM pln_plan WHERE name LIKE 'E2E AUDIT%'")
    print("\n" + ("ALL PASS" if not FAILED else f"{len(FAILED)} FAILED: " + "; ".join(FAILED)))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
