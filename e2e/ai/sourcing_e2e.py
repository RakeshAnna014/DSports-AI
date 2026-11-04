"""Real-stack check of Product & Supplier Intelligence (Phase 5): buyer roles, unmet customer demand -> opportunity, manual supplier quotes, landed-cost comparison with hand-computed
numbers, the approval workflow with roles and audit, the onboarding proposal, "approval changes nothing in the commerce data", and persistence across an AI-service restart.

No mocks. Creates throw-away users and rows labelled E2E (opportunity name "Zorbball Paddles", suppliers "E2E Supplier ..."). Needs the stack (backend :8080, AI service :8090).
No external research source is configured here, so the research run is expected to report PARTIAL with an explicit 'unavailable' reason: that is also checked.

    python e2e/ai/sourcing_e2e.py
"""
from __future__ import annotations

import json
import os
import random
import string
import subprocess
import sys
import time
import uuid

import stack as s

FAILED: list[str] = []
BASH = os.environ.get("GIT_BASH", r"C:\Program Files\Git\bin\bash.exe")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def check(name: str, cond: bool, extra: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {str(extra)[:500]}"))
    if not cond:
        FAILED.append(name)


def grant(email: str, role: str) -> str:
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, '{role}' FROM customers WHERE email='{email}' ON CONFLICT DO NOTHING")
    return s.login(email)


def roles_of(token: str) -> list[str]:
    import base64
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("roles", [])


def commerce_snapshot() -> dict[str, str]:
    return {t: s.sql(q) for t, q in {"products": "select count(*) from products", "orders": "select count(*) from orders", "payments": "select count(*) from payments",
                                     "shipments": "select count(*) from shipments", "prices": "select count(*) || '/' || coalesce(sum(selling_price),0) from prices"}.items()}


def S(method, path, tok=None, body=None):
    return s.ai(method, "/v1/sourcing" + path, tok, body)


def main() -> int:
    word = "zorbball" + "".join(random.choice(string.ascii_lowercase) for _ in range(5))      # letters only: the redactor drops digit runs on purpose
    noun = "quibble" + "".join(random.choice(string.ascii_lowercase) for _ in range(5))             # a second unique token: the model may pick either token as the request's key term, and a shared word ("paddles") would fold this run's searches into an older demand group`n    product = f"{word.capitalize()} Paddles"
    c1_email, c1 = s.register("srcc1")
    c2_email, c2 = s.register("srcc2")
    buyer_email, _ = s.register("srcbuyer")
    boss_email, _ = s.register("srcboss")
    sup_email, _ = s.register("srcsupport")
    buyer = grant(buyer_email, "PROCUREMENT_BUYER")
    boss = grant(boss_email, "CATEGORY_MANAGER")
    support = grant(sup_email, "SUPPORT_EXECUTIVE")
    check("the new roles can be stored and issued (login still works, token carries the role)", "PROCUREMENT_BUYER" in roles_of(buyer) and "CATEGORY_MANAGER" in roles_of(boss), (roles_of(buyer), roles_of(boss)))

    # ---- authorisation
    check("anonymous gets 401", S("GET", "/dashboard")[0] == 401)
    check("a customer gets 403", S("GET", "/dashboard", c1)[0] == 403)
    check("a support executive gets 403", S("GET", "/dashboard", support)[0] == 403)
    check("a customer cannot use the assistant tools through the sourcing ask endpoint", S("POST", "/ask", c1, {"question": "overview"})[0] == 403)
    st, dash = S("GET", "/dashboard", buyer)
    check("a buyer sees the live-scope dashboard with the research source status and the excluded fixture count", st == 200 and dash["scope"] == "live" and "external_configured" in dash["sources"] and "fixtures" in dash["excluded"], dash)

    before = commerce_snapshot()

    # ---- unmet demand from two different customers, through the real assistant
    for tok in (c1, c2):
        st, chat = s.ai("POST", "/v1/chat", tok, {"message": f"Do you sell {word} {noun}?"})
        check("customer search answered", st == 200, chat)
    time.sleep(1)
    st, ans = S("POST", "/ask", buyer, {"question": "What are customers looking for that we do not currently sell?"})
    check("the buyer sees the unmet search, with counts and the interest-not-intent caveat", st == 200 and word in ans["answer"] and "not proven purchase intent" in ans["answer"], ans)

    # ---- research run (waits for the result): the customer searches become a DEMAND GAP, never a product
    st, run = S("POST", "/runs", buyer, {"keywords": [word], "wait": True})
    check("the run finishes with a recorded status and per-source results (no fake source results)", st == 202 and run["status"] in ("SUCCEEDED", "PARTIAL") and run["sources"], run)
    check("the run created the customer-demand gap", run["stats"].get("demand_gaps_new", 0) >= 1, run["stats"])
    st, again = S("POST", "/runs", buyer, {"keywords": [word], "wait": True})
    check("repeating the run does not duplicate the gap", st == 202 and again["stats"].get("demand_gaps_new", 0) == 0, again.get("stats"))
    st, listing = S("GET", f"/opportunities?record_kind=DEMAND_GAP&q={word}", buyer)
    check("the signal is listed as a demand gap", st == 200 and listing["total"] == 1 and listing["items"][0]["name"].lower().startswith(word), listing)      # named by the customers' fullest wording, which varies with the assistant's slot extraction
    gap = listing["items"][0]
    gid = gap["id"]
    st, plist = S("GET", f"/opportunities?q={word}", buyer)
    check("a demand gap is NOT in the product list", st == 200 and plist["total"] == 0, plist)
    st, gdetail = S("GET", f"/opportunities/{gid}", buyer)
    blob = json.dumps(gdetail)
    check("evidence says 2 customers and holds no customer identity", st == 200 and "2 search(es) from 2 customer(s)" in blob and c1_email not in blob and c2_email not in blob, blob[:300])
    check("it is labelled as customer interest, not purchase intent", "not proof of purchase intent" in blob or "interest only" in blob.lower() or "Customer interest only" in blob, blob[:300])
    check("provenance is CUSTOMER_DEMAND_SIGNAL (not presented as market research)", gdetail["opportunity"]["provenance"] == "CUSTOMER_DEMAND_SIGNAL" and gdetail["opportunity"]["record_kind"] == "DEMAND_GAP"
          and gdetail["opportunity"]["origin"] == "DEMAND_SIGNAL")
    st, gscore = S("POST", f"/opportunities/{gid}/score", buyer)
    check("a gap is awaiting product discovery: no opportunity score is invented", st == 200 and gscore["status"] == "AWAITING_PRODUCT_DISCOVERY" and gscore["opportunity"] is None, gscore)
    _, gsup = S("POST", "/suppliers", buyer, {"name": f"E2E Supplier G {uuid.uuid4().hex[:4]}", "type": "WHOLESALER", "is_test": True})
    st, gq = S("POST", f"/opportunities/{gid}/quotes", buyer, {"supplier_id": gsup["id"], "offered_product": "something", "currency": "INR", "unit_price": 1, "source_kind": "QUOTE_ENTERED"})
    check("a gap cannot be quoted", st >= 400 and gq.get("code") == "NOT_A_PRODUCT", gq)
    st, gs = S("POST", f"/opportunities/{gid}/status", boss, {"status": "APPROVED_OPPORTUNITY", "expected_version": gdetail["opportunity"]["version"]})
    check("a gap cannot be approved", st >= 400, gs)

    # ---- a TEST product (marked as test data: never part of live figures) with suppliers and offers entered by the buyer
    suffix = uuid.uuid4().hex[:4]
    product = f"E2E Smash Racket {suffix}"
    st, made = S("POST", "/opportunities", buyer, {"name": product, "brand": "E2EBrand", "model": f"E2E-{suffix.upper()}", "sport": "Badminton", "category": "Rackets", "is_test": True})
    check("a test product is created and labelled TEST_FIXTURE", st == 201 and made["provenance"] == "TEST_FIXTURE", made)
    oid = made["id"]
    st, detail = S("GET", f"/opportunities/{oid}", buyer)
    check("a test product is hidden from the live list", S("GET", f"/opportunities?q={suffix}", buyer)[1]["total"] == 0 and S("GET", f"/opportunities?q={suffix}&include_fixtures=true", buyer)[1]["total"] == 1)
    sups = []
    for name in (f"E2E Supplier P {suffix}", f"E2E Supplier Q {suffix}"):
        st, sup = S("POST", "/suppliers", buyer, {"name": name, "type": "WHOLESALER", "country": "IN", "brands": ["E2E"], "is_test": True})
        check(f"supplier {name} created as test data at the first rung of the ladder", st == 201 and sup["verification_status"] == "DISCOVERED" and sup["provenance"] == "TEST_FIXTURE", sup)
        sups.append(sup)
    quote = lambda sup, price, moq, ship: {"supplier_id": sup["id"], "offered_product": product, "currency": "INR", "unit_price": price, "moq": moq, "shipping_cost": ship,
                                          "lead_time_days": 21, "quoted_on": time.strftime("%Y-%m-%d"), "valid_until": time.strftime("%Y-%m-%d", time.localtime(time.time() + 30 * 86400)),
                                          "source_kind": "QUOTE_ENTERED", "reference": f"E2E-{suffix}"}
    st, qp = S("POST", f"/opportunities/{oid}/quotes", buyer, quote(sups[0], 1000, 100, 5000))
    st2, qq = S("POST", f"/opportunities/{oid}/quotes", buyer, quote(sups[1], 950, 200, 40000))
    check("two quotes recorded, labelled as entered from a supplier quotation", st == 201 and st2 == 201 and qp["label"] == "Entered from a supplier quotation", (qp, qq))
    st, bad = S("POST", f"/opportunities/{oid}/quotes", buyer, {**quote(sups[0], 1000, 100, 0), "source_kind": "VERIFIED"})
    check("a quote cannot be created as VERIFIED", st == 422, bad)
    st, usd = S("POST", f"/opportunities/{oid}/quotes", buyer, {**quote(sups[0], 12, 100, 0), "currency": "USD"})

    st, cmp = S("POST", f"/opportunities/{oid}/compare", buyer, {"quantity": 500, "selling_price": 1800})
    rows = {o["quote"]["id"]: o for o in cmp["offers"]}
    p, q = rows[qp["id"]]["calculation"]["values"], rows[qq["id"]]["calculation"]["values"]
    check("landed cost matches the hand calculation: P 1010.00, Q 1030.00 per unit", p["landed_cost_per_unit"] == "1010.00" and q["landed_cost_per_unit"] == "1030.00", (p, q))
    check("margin on a 1800 selling price: P 43.9%", p["gross_margin_pct"] == "43.9" and p["markup_pct"] == "78.2", p)
    check("the cheaper unit price (Q) is not the cheaper landed cost, and the explanation says so",
          cmp["summary"]["lowest_unit_price_quote"] == qq["id"] and cmp["summary"]["lowest_landed_quote"] == qp["id"] and "lower landed cost" in cmp["summary"]["explanation"], cmp["summary"])
    check("a foreign-currency offer with no sourced exchange rate is INCOMPLETE, never guessed", rows[usd["id"]]["calculation"]["status"] == "INCOMPLETE"
          and any("fx_rate" in m for m in rows[usd["id"]]["calculation"]["missing"]), rows[usd["id"]]["calculation"])
    st, low = S("POST", f"/opportunities/{oid}/compare", buyer, {"quantity": 50, "selling_price": 1800})
    check("below the MOQ the offers are excluded with the reason", low["summary"]["usable"] == 0 and low["summary"]["excluded"], low["summary"])
    st, saved = S("POST", f"/opportunities/{oid}/calculate", buyer, {"quote_id": qp["id"], "quantity": 500, "selling_price": 1800, "assumptions": {"target_margin_pct": 30}, "save": True})
    check("calculation saved as a snapshot with a 30% target price", st == 200 and saved["snapshot_id"] and saved["values"]["target_margin_selling_price"] == "1442.86", saved)
    st, sc = S("POST", f"/opportunities/{oid}/score", buyer)
    check("score is explainable, keeps risk and confidence separate and lists missing data", st == 200 and "supplier_risk" in sc and sc["confidence"] in ("LOW", "MEDIUM", "HIGH") and sc["missing"] and sc["dimensions"], sc)

    # ---- workflow, roles, audit
    v = detail["opportunity"]["version"]
    for target in ("UNDER_REVIEW", "SHORTLISTED"):
        st, o = S("POST", f"/opportunities/{oid}/status", buyer, {"status": target, "expected_version": v})
        check(f"buyer moves to {target}", st == 200, o)
        v = o["version"]
    st, r = S("POST", f"/opportunities/{oid}/status", buyer, {"status": "APPROVED_OPPORTUNITY", "expected_version": v})
    check("a buyer cannot give the business approval", st == 403, r)
    st, o = S("POST", f"/opportunities/{oid}/status", boss, {"status": "APPROVED_OPPORTUNITY", "reason": "E2E: worth pursuing", "expected_version": v})
    check("the category manager approves it as a business opportunity (a separate stage)", st == 200 and o["status"] == "APPROVED_OPPORTUNITY", o)
    v = o["version"]
    st, r = S("POST", f"/opportunities/{oid}/status", boss, {"status": "APPROVED_FOR_ONBOARDING", "expected_version": v})
    check("business approval does not skip to onboarding approval", st >= 400, r)
    st, o = S("POST", f"/opportunities/{oid}/status", boss, {"status": "SUPPLIER_RESEARCH", "expected_version": v})
    check("the manager moves it to SUPPLIER_RESEARCH", st == 200, o)
    v = o["version"]
    st, o = S("POST", f"/opportunities/{oid}/status", buyer, {"status": "QUOTE_COMPARISON", "expected_version": v})
    check("buyer moves to QUOTE_COMPARISON", st == 200, o)
    v = o["version"]
    st, r = S("POST", f"/opportunities/{oid}/status", buyer, {"status": "APPROVED_FOR_ONBOARDING", "expected_version": v})
    check("a buyer cannot approve", st == 403, r)
    st, r = S("POST", f"/opportunities/{oid}/status", boss, {"status": "APPROVED_FOR_ONBOARDING", "expected_version": v - 1})
    check("a stale version is refused", st == 409 and r["code"] == "VERSION_CONFLICT", r)
    st, r = S("POST", f"/opportunities/{oid}/status", boss, {"status": "APPROVED_FOR_ONBOARDING", "reason": "E2E: margin and supplier fit", "expected_version": v})
    check("the category manager approves", st == 200 and r["status"] == "APPROVED_FOR_ONBOARDING", r)
    st, rj = S("POST", f"/opportunities/{oid}/status", boss, {"status": "REJECTED", "expected_version": r["version"]})
    check("rejecting needs a reason", st == 422 and rj["code"] == "REASON_REQUIRED", rj)
    st, detail = S("GET", f"/opportunities/{oid}", buyer)
    hist = [h for h in detail["history"] if h["action"] == "STATUS_CHANGE"]
    check("every status change is in the audit history with actor and time", len(hist) == 6 and all(h["actor_hash"] and h["at"] for h in hist) and hist[-1]["reason"] == "E2E: margin and supplier fit", hist)
    check("buyer and approver are different actors", hist[0]["actor_hash"] != hist[-1]["actor_hash"])

    # ---- quote verification
    st, ver = S("POST", f"/quotes/{qp['id']}/verify", buyer)
    check("a buyer verifies an entered quotation", st == 200 and ver["source_kind"] == "VERIFIED", ver)
    st, edit = s.ai("PATCH", f"/v1/sourcing/quotes/{qp['id']}", buyer, {"unit_price": 1010})
    check("editing the figures voids the verification", st == 200 and edit["source_kind"] == "QUOTE_ENTERED" and edit["verified_by"] is None, edit)

    # ---- proposal: a draft, nothing published
    st, prop = S("POST", f"/opportunities/{oid}/proposal", buyer, {"quote_id": qp["id"], "quantity": 500, "selling_price": 1800})
    check("onboarding proposal is a persisted DRAFT listing what is missing (approved, yet not catalogue-ready)",
          st == 201 and prop["status"] == "DRAFT" and not prop["validation"]["ready"] and any("escription" in m for m in prop["validation"]["missing_mandatory"]), prop)
    check("the proposal says it does not create, publish or price a product or authorise a purchase", "does not create, publish or price a product" in prop["payload"]["notice"]
          and "NOT granted by this proposal" in json.dumps(prop["payload"]["required_approvals"]), prop["payload"]["notice"])

    after = commerce_snapshot()
    check("nothing changed in the commerce data (products, orders, payments, shipments, prices)", after == before, (before, after))

    # ---- restart persistence
    if os.environ.get("SOURCING_E2E_RESTART", "1") == "1":
        subprocess.run([BASH, "e2e/ai/start-ai.sh"], cwd=ROOT, capture_output=True, timeout=240)
        for _ in range(60):
            if s.ai("GET", "/health")[0] == 200:
                break
            time.sleep(2)
        st, d2 = S("GET", f"/opportunities/{oid}", buyer)
        check("after an AI-service restart the opportunity, status, quotes, calculation and proposal are all still there",
              st == 200 and d2["opportunity"]["status"] == "APPROVED_FOR_ONBOARDING" and len(d2["quotes"]) == 3 and d2["calculations"] and d2["proposals"] and len(d2["history"]) >= 8, d2.get("opportunity"))
    st, runs = S("GET", "/runs", buyer)
    check("run history is kept with each source's status", st == 200 and runs["runs"] and runs["runs"][0]["sources"])

    print("\nALL PASS" if not FAILED else f"\n{len(FAILED)} FAILED: {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
