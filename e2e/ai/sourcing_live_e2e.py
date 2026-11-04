"""LIVE research check for Product & Supplier Intelligence: runs the REAL provider against the manufacturer's real website (Victor Sport, robots.txt honoured), then follows
the products through the real stack. No mock transport. Needs the stack plus outbound internet; if the site is unreachable the run reports it and this script says so instead of passing.

    python e2e/ai/sourcing_live_e2e.py [keyword]

Verifies: real products with source URLs and specifications, images with provenance and a validation status, a discovered (UNVERIFIED) supplier, catalogue matching, idempotent re-run
(no duplicates), live-only metrics (fixtures/legacy excluded), supplier quotation -> landed cost -> margin tied to THIS product, an independent readiness check, the separate approval
stages, and persistence across an AI-service restart. Records it creates are labelled with the buyer-entered supplier "E2E Live Supplier ..." (test fixture).
"""
from __future__ import annotations

import os
import random
import string
import subprocess
import sys
import time

import stack as s
from sourcing_e2e import BASH, ROOT, FAILED, check, grant

KEYWORD = sys.argv[1] if len(sys.argv) > 1 else "thruster"


def S(method, path, tok=None, body=None):
    return s.ai(method, "/v1/sourcing" + path, tok, body)


def wait_run(tok, run_id, seconds=300):
    end = time.time() + seconds
    while time.time() < end:
        code, run = S("GET", f"/runs/{run_id}", tok)
        if code == 200 and run["status"] != "RUNNING":
            return run
        time.sleep(3)
    return None


def main() -> int:
    buyer_email, _ = s.register("livebuyer")
    boss_email, _ = s.register("liveboss")
    buyer, boss = grant(buyer_email, "PROCUREMENT_BUYER"), grant(boss_email, "CATEGORY_MANAGER")

    code, before = S("GET", "/dashboard", buyer)
    check("dashboard is live-scope and reports excluded legacy/fixture rows separately", code == 200 and before["scope"] == "live" and "excluded" in before, before)
    print("  excluded before:", before.get("excluded"), " live products before:", before.get("total_products"))
    code, srcs = S("GET", "/sources", buyer)
    print("  sources:", srcs if code == 200 else code)

    code, started = S("POST", "/runs", buyer, {"keywords": [KEYWORD]})
    check("a research run starts asynchronously (202, RUNNING)", code == 202 and started["status"] in ("RUNNING", "SUCCEEDED", "PARTIAL"), started)
    run = wait_run(buyer, started["id"])
    check("the run finishes in a recorded state", run is not None and run["status"] in ("SUCCEEDED", "PARTIAL"), run)
    if not run or run["status"] not in ("SUCCEEDED", "PARTIAL"):
        print("LIVE RESEARCH DID NOT COMPLETE:", run)
        return 1
    st = run["stats"]
    print("  run stats:", st, "\n  sources:", run["sources"])
    check("real product pages were read and products persisted", st.get("created", 0) + st.get("refreshed", 0) >= 1, st)
    code, res = S("GET", f"/runs/{run['id']}/results", buyer)
    prods = res["products"] if code == 200 else []
    check("the run's results list products with their source URL", prods and all(str(p.get("source_url", "")).startswith("https://www.victorsport.com/") for p in prods), prods[:1])

    code, listing = S("GET", f"/opportunities?q={KEYWORD}", buyer)
    items = listing["items"] if code == 200 else []
    check("products are in the live list as LIVE_EXTERNAL_SOURCE product candidates", items and all(i["provenance"] == "LIVE_EXTERNAL_SOURCE" and i["record_kind"] == "PRODUCT_CANDIDATE" for i in items), items[:1])
    first = items[0]
    code, full = S("GET", f"/opportunities/{first['id']}", buyer)
    d = {**full["opportunity"], "images": full["images"], "missing_specifications": full["missing_specifications"], "evidence": full["evidence"]}
    check("detail carries identity, source title, URL, specifications and evidence", code == 200 and d["brand"] == "Victor" and d["product_url"] and d["specs"] and d["evidence"], {k: d.get(k) for k in ("brand", "product_url")})
    print("  product:", d["name"], "| SKU:", d.get("mfr_sku"), "| model:", d.get("model"), "| specs:", len(d["specs"]), "| missing:", d.get("missing_specifications"))
    imgs = d.get("images", [])
    check("images are stored as references with provenance, a match level and a validation status", imgs and all(i["source_page_url"] and i["match_level"] and i["status"] for i in imgs), imgs[:1])
    print("  images:", [(i["match_level"], i["status"]) for i in imgs])
    check("catalogue matching is reported (never auto-merged)", d.get("catalogue_match", {}).get("level") in ("EXACT", "PROBABLE_DUPLICATE", "SIMILAR", "NONE", "NEEDS_REVIEW"), d.get("catalogue_match"))
    # The page publishes no price, so research must not create one. On a re-run a buyer-entered quotation from an earlier run may exist; what must never exist is a price that research or an AI estimate supplied.
    check("no price is invented: the page has none, so research created no quotation (any quotation here was entered by a buyer) and no calculation without one",
          all(q.get("origin") == "MANUAL" and q.get("source_kind") != "AI_ESTIMATE" for q in full["quotes"]) and (full["quotes"] or not full["calculations"]), full["quotes"])

    code, sups = S("GET", "/suppliers", buyer)
    discovered = [x for x in (sups["items"] if code == 200 else []) if x["provenance"] == "LIVE_EXTERNAL_SOURCE"]
    check("a supplier candidate was discovered from the brand's official page and is NOT verified", discovered and all(x["verification_status"] == "DISCOVERED" for x in discovered), discovered[:1])

    code, again = S("POST", "/runs", buyer, {"keywords": [KEYWORD], "wait": True})
    run2 = again if again.get("status") != "RUNNING" else wait_run(buyer, again["id"])
    check("re-running the same research creates no duplicates", run2 and run2["stats"].get("created", 1) == 0, run2 and run2["stats"])
    code, listing2 = S("GET", f"/opportunities?q={KEYWORD}", buyer)
    check("the live product count is unchanged after the re-run", listing2["total"] == listing["total"], (listing["total"], listing2["total"]))

    # ---- supplier quotation -> landed cost -> margin tied to THIS product
    word = "".join(random.choice(string.ascii_lowercase) for _ in range(5))
    code, sup = S("POST", "/suppliers", buyer, {"name": f"E2E Live Supplier {word}", "type": "WHOLESALER", "is_test": True})
    check("a buyer can add a supplier (test-labelled)", code == 201, sup)
    qcode, q = S("POST", f"/opportunities/{first['id']}/quotes", buyer, {"supplier_id": sup["id"], "offered_product": d["name"], "currency": "INR", "unit_price": 1200, "moq": 50,
                                                                         "quoted_on": time.strftime("%Y-%m-%d"), "valid_until": "2030-12-31", "source_kind": "QUOTE_ENTERED"})
    check("a manual quotation is accepted as a first-class entry", qcode == 201, q)
    ccode, calc = S("POST", f"/opportunities/{first['id']}/calculate", buyer, {"quote_id": q["id"], "quantity": 100, "selling_price": 2500, "save": True})
    check("landed cost and margin are computed and tied to this product, offer, quantity and currency",
          ccode == 200 and calc["basis"]["opportunity_id"] == first["id"] and calc["basis"]["quote_id"] == q["id"] and calc["basis"]["quantity"] == 100 and calc["basis"]["currency"] == "INR", calc)
    print("  margin:", calc["values"].get("gross_margin_pct"), "% |", calc["basis"]["label"])
    if len(items) > 1:
        other = items[1]
        _, od = S("GET", f"/opportunities/{other['id']}", buyer)
        check("a different product does not inherit that margin", not od["calculations"], od["calculations"][:1])
    scode, sc = S("POST", f"/opportunities/{first['id']}/score", buyer)
    check("scoring is v2 with separate outputs and a financial status", scode == 200 and sc["model_version"] == "v2" and sc["financial_status"] == "QUOTED", sc)
    print("  score:", {k: sc.get(k) for k in ("opportunity", "confidence", "completeness", "risk", "financial_status")})

    # ---- approval vs readiness
    rcode, ready = S("GET", f"/opportunities/{first['id']}/readiness?quote_id={q['id']}&quantity=100&selling_price=2500", buyer)
    print("  readiness:", ready.get("ready"), ready.get("missing"))
    v = d["version"]
    for t in ("UNDER_REVIEW", "SHORTLISTED"):
        c, r = S("POST", f"/opportunities/{first['id']}/status", buyer, {"status": t, "expected_version": v})
        v = r["version"] if c == 200 else v
    c, r = S("POST", f"/opportunities/{first['id']}/status", buyer, {"status": "APPROVED_OPPORTUNITY", "expected_version": v})
    check("a buyer cannot give the business approval", c == 403, r)
    c, r = S("POST", f"/opportunities/{first['id']}/status", boss, {"status": "APPROVED_OPPORTUNITY", "reason": "e2e", "expected_version": v})
    if d.get("mfr_sku") or d.get("model"):
        check("an approver can approve the opportunity, and that is not onboarding approval", c == 200 and r["status"] == "APPROVED_OPPORTUNITY", r)
    else:
        check("approval is refused when the page gave no model/SKU (nothing invented)", c in (409, 422), r)
    c, after = S("GET", f"/opportunities/{first['id']}", boss)
    check("readiness is independent of the decision status", "readiness" in after and after["opportunity"]["status"] != "APPROVED_FOR_ONBOARDING", after["opportunity"]["status"])

    # ---- restart persistence
    subprocess.run([BASH, os.path.join(ROOT, "e2e", "ai", "start-ai.sh")], check=False, capture_output=True)
    code, listing3 = S("GET", f"/opportunities?q={KEYWORD}", buyer)
    check("everything is still there after an AI-service restart", code == 200 and listing3["total"] == listing["total"], code)
    code, dash = S("GET", "/dashboard", buyer)
    print("  dashboard now: products", dash.get("total_products"), "| excluded", dash.get("excluded"), "| with_supplier_quotes", dash.get("with_supplier_quotes"))
    print("\nFAILED:" if FAILED else "\nALL LIVE CHECKS PASSED", FAILED or "")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
