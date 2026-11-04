"""Real-stack verification of the Management Feedback features (tickets, profiles/family, budget-aware recommendations).

Every call goes to the RUNNING Java backend (:8080), the RUNNING AI service (:8090) and the real PostgreSQL. Nothing is mocked. The only SQL used is
what the other harnesses already use: granting a role (there is no role-admin API) and reading counts back to compare with API answers.

Usage: python e2e/ai/mgmt_feedback_e2e.py        exit 0 = every check passed, 1 = a check failed
"""
from __future__ import annotations

import json
import sys
import time
import uuid

import stack as s
from support_data import buy_lines

RESULTS: list[tuple[str, bool, str]] = []
HEAD_RACQUET = "00000000-0000-0000-0000-000000001070"     # Head Tennis Racquet - Performance
SS_BAT = s.P_BAT_SS                                         # SS Cricket Bat - Prime
MRF_BAT = s.P_BAT_MRF
BALL_KOOK = "00000000-0000-0000-0000-000000001004"          # Kookaburra Cricket Ball - Red


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    return bool(ok)


def reco(token: str | None, **params) -> tuple[int, dict]:
    q = "&".join(f"{k}={v}" for k, v in params.items() if v is not None)
    return s.ai("GET", f"/v1/recommendations?{q}", token)


def main() -> int:
    s.restock(800)
    run = uuid.uuid4().hex[:8]
    alice_email, alice = s.register("mf-alice")
    bob_email, bob = s.register("mf-bob")
    admin_email, _ = s.register("mf-admin")
    admin = s.make_admin(admin_email)
    staff_email, _ = s.register("mf-staff")
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, 'SUPPORT_EXECUTIVE' FROM customers WHERE email='{staff_email}' ON CONFLICT DO NOTHING")
    staff = s.login(staff_email)

    # ============================================================== 1. a customer creates a ticket
    code, order = None, s.buy(alice, MRF_BAT, 1)
    s.pay(alice, order)
    key = f"mf-{run}-1"
    code, created = s.dsports("POST", "/api/v1/support/cases", alice, {
        "category": "ORDER_ISSUE", "subject": f"MF-{run} order page shows the wrong total", "description": "The total on my order page does not match my cart.",
        "orderId": order["id"]}, {"Idempotency-Key": key})
    case = created["supportCase"] if code in (200, 201) else {}
    number = case.get("caseNumber", "")
    check("1. customer creates a ticket and receives a real ticket number", code in (200, 201) and number.startswith("CASE-") and case.get("category") == "ORDER_ISSUE",
          f"{code} {created}")
    in_db = s.sql(f"select count(*) from support_cases where case_number='{number}' and category='ORDER_ISSUE'")
    check("1. the ticket is persisted in the database", in_db == "1", in_db)
    code2, again = s.dsports("POST", "/api/v1/support/cases", alice, {
        "category": "ORDER_ISSUE", "subject": f"MF-{run} order page shows the wrong total", "description": "The total on my order page does not match my cart.",
        "orderId": order["id"]}, {"Idempotency-Key": key})
    check("1. retrying the same submission does not create a duplicate", code2 in (200, 201) and again["supportCase"]["caseNumber"] == number and again["outcome"] != "CREATED",
          f"{code2} {again.get('outcome')}")
    code3, tech = s.dsports("POST", "/api/v1/support/cases", alice, {"category": "TECHNICAL_ISSUE", "subject": f"MF-{run} login page error",
                            "description": "The login page shows an error after I enter my password."}, {"Idempotency-Key": f"mf-{run}-2"})
    check("1. a website/technical ticket needs no order", code3 in (200, 201) and tech["supportCase"]["category"] == "TECHNICAL_ISSUE", f"{code3} {tech}")
    code4, bad = s.dsports("POST", "/api/v1/support/cases", alice, {"category": "NOT_A_CATEGORY", "subject": "x", "description": "y"}, {"Idempotency-Key": f"mf-{run}-3"})
    check("1. an unknown category is rejected by the backend", code4 == 400, f"{code4} {bad}")

    # ============================================================== 2. the customer sees it in their list (and nobody else can)
    code, mine = s.dsports("GET", "/api/v1/support/cases", alice)
    check("2. the customer's ticket list contains the ticket with its status", code == 200 and any(c["caseNumber"] == number and c["status"] == "OPEN" for c in mine), f"{code}")
    code, theirs = s.dsports("GET", "/api/v1/support/cases", bob)
    check("2. another customer's list does not contain it", code == 200 and all(c["caseNumber"] != number for c in theirs))
    code, peek = s.dsports("GET", f"/api/v1/support/cases/{number}", bob)
    check("2. another customer cannot open it by number (404, indistinguishable from missing)", code == 404, f"{code}")
    code, own = s.dsports("GET", f"/api/v1/support/cases/{number}", alice)
    check("2. the owner can open it", code == 200 and own["caseNumber"] == number)
    code, forbidden = s.dsports("GET", "/api/v1/support/staff/cases", alice)
    check("2. a customer token is rejected by the staff API (403)", code == 403, f"{code}")

    # ============================================================== 3. staff finds it and changes its status
    code, found = s.dsports("GET", f"/api/v1/support/staff/cases?q={number}", staff)
    check("3. staff finds the same ticket by ticket number", code == 200 and found["total"] == 1 and found["items"][0]["caseNumber"] == number, f"{code} {found}")
    code, before = s.dsports("GET", "/api/v1/support/staff/analytics", staff)
    db_open = int(s.sql("select count(*) from support_cases where status='OPEN'"))
    check("4. the dashboard OPEN count equals the database before the change", code == 200 and before["byStatus"].get("OPEN") == db_open, f"{before.get('byStatus')} vs {db_open}")
    code, detail = s.dsports("GET", f"/api/v1/support/staff/cases/{number}", staff)
    version = detail["version"]
    code, taken = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/assign", staff, {"takeIt": True, "expectedVersion": version})
    check("3. staff takes the ticket and it starts being investigated", code == 200 and taken["status"] == "INVESTIGATING" and taken["assignedTo"], f"{code} {taken}")
    code, prio = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/priority", staff, {"priority": "URGENT", "reason": "Blocks the customer's payment", "expectedVersion": taken["version"]})
    check("3. staff changes the priority with a reason", code == 200 and prio["priority"] == "URGENT" and any("Blocks the customer" in r for r in prio["priorityReasons"]), f"{code} {prio}")
    code, nopr = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/priority", staff, {"priority": "LOW", "reason": "", "expectedVersion": prio["version"]})
    check("3. a priority change without a reason is rejected", code == 400, f"{code}")
    code, note = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/notes", staff, {"body": "INTERNAL-ONLY check the payment gateway log", "expectedVersion": prio["version"]})
    code, reply = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/replies", staff, {"body": "We are looking into your order total.", "expectedVersion": note["version"]})
    code, waiting = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/status", staff, {"status": "WAITING_FOR_CUSTOMER", "note": "Need a screenshot", "expectedVersion": reply["version"]})
    check("3. staff changes the status to WAITING_FOR_CUSTOMER", code == 200 and waiting["status"] == "WAITING_FOR_CUSTOMER", f"{code} {waiting}")
    code, bad_move = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/status", staff, {"status": "OPEN", "expectedVersion": waiting["version"]})
    check("3. an invalid status transition is rejected by the backend (409)", code == 409, f"{code} {bad_move}")
    code, stale = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/notes", staff, {"body": "stale", "expectedVersion": 1})
    check("3. a stale view is rejected (optimistic locking)", code == 409, f"{code}")
    code, view = s.dsports("GET", f"/api/v1/support/cases/{number}", alice)
    txt = json.dumps(view)
    check("3. the customer sees the support reply and the new status after refresh", code == 200 and view["status"] == "WAITING_FOR_CUSTOMER" and "We are looking into your order total." in txt)
    check("3. the customer never sees internal notes or priority-change audit", "INTERNAL-ONLY" not in txt and "PRIORITY_CHANGED" not in txt and "Blocks the customer" not in txt)
    code, cust_reply = s.dsports("POST", f"/api/v1/support/cases/{number}/messages", alice, {"body": "Here is the screenshot description."})
    check("3. the customer's reply moves a waiting ticket back to investigation", code == 200 and cust_reply["status"] == "INVESTIGATING", f"{code} {cust_reply.get('status')}")
    code, sfind = s.dsports("GET", "/api/v1/support/staff/cases?createdFrom=2020-01-01&createdTo=2999-01-01&category=ORDER_ISSUE&priority=URGENT&assignedTo=" + detail["customerId"], staff)
    code_b, sfind2 = s.dsports("GET", f"/api/v1/support/staff/cases?category=ORDER_ISSUE&priority=URGENT&createdFrom=2020-01-01&createdTo=2999-01-01&q={number}", staff)
    check("3. staff filters (category, priority, dates, ticket number) work and are paginated", code_b == 200 and sfind2["total"] == 1 and sfind2["page"] == 0 and sfind2["size"] == 25, f"{code_b} {sfind2}")

    # ============================================================== 4. the dashboard reflects the persisted change
    code, resolved = s.dsports("POST", f"/api/v1/support/staff/cases/{number}/resolve", staff,
                               {"outcome": "ANSWERED", "note": "Explained the total", "evidenceRefs": [], "expectedVersion": cust_reply["version"]})
    code, after = s.dsports("GET", "/api/v1/support/staff/analytics", staff)
    db = {r.split("|")[0]: int(r.split("|")[1]) for r in s.sql("select status||'|'||count(*) from support_cases group by status").splitlines()}
    check("4. after the change the dashboard by-status counts equal the database", code == 200 and all(after["byStatus"].get(k, 0) == v for k, v in db.items()), f"{after['byStatus']} vs {db}")
    check("4. the RESOLVED count rose by one and persists after refresh", after["byStatus"].get("RESOLVED", 0) == before["byStatus"].get("RESOLVED", 0) + 1, f"{before['byStatus']} -> {after['byStatus']}")
    by_cat_db = {r.split("|")[0]: int(r.split("|")[1]) for r in s.sql("select category||'|'||count(*) from support_cases group by category").splitlines()}
    check("4. counts by category equal the database", after["byCategory"] == by_cat_db, f"{after['byCategory']} vs {by_cat_db}")

    # ============================================================== AI: a ticket through the chatbot (confirmation, idempotency, no false success)
    code, proposal = s.ai("POST", "/v1/chat", alice, {"message": f"MF-{run} the website will not let me log in, it keeps showing an error after my password"})
    check("AI. a technical problem is classified and a ticket is only PROPOSED", code == 200 and proposal.get("requiresConfirmation") and not s.sql(f"select 1 from support_cases where subject like '%MF-{run} the website%'"),
          f"{code} {proposal.get('message', '')[:120]}")
    if proposal.get("requiresConfirmation"):
        token = proposal["confirmationToken"]
        code, done = s.ai("POST", "/v1/confirm", alice, {"confirmationToken": token, "conversationId": proposal["conversationId"]})
        ai_numbers = s.sql("select case_number from support_cases where source='AI_ASSISTANT' and category='TECHNICAL_ISSUE' order by created_at desc limit 1")
        check("AI. after confirmation the backend ticket number is returned (not invented)", code == 200 and ai_numbers in done.get("message", ""), f"{code} {done.get('message', '')[:160]} / {ai_numbers}")
        count_before = s.sql("select count(*) from support_cases")
        code, replay = s.ai("POST", "/v1/confirm", alice, {"confirmationToken": token, "conversationId": proposal["conversationId"]})
        check("AI. replaying the confirmation creates no duplicate ticket", s.sql("select count(*) from support_cases") == count_before, f"{code}")
        code, other = s.ai("POST", "/v1/confirm", bob, {"confirmationToken": token, "conversationId": proposal["conversationId"]})
        check("AI. another customer cannot use the confirmation token", code in (400, 401, 403, 404, 409, 410) or other.get("status") != "ok", f"{code}")
    code, passing = s.ai("POST", "/v1/chat", alice, {"message": "Does your website have a size guide for cricket gloves?"})
    check("AI. a passing mention of the website does not create or propose a ticket", code == 200 and not passing.get("requiresConfirmation"))

    # ============================================================== 5. a customer saves sports and budget preferences (and the family profiles)
    code, overview = s.dsports("GET", "/api/customers/me/profiles", alice)
    owner_id = overview["profiles"][0]["id"] if code == 200 else ""
    check("5. a new customer has an owner profile with no preferences", code == 200 and overview["profiles"][0]["kind"] == "OWNER" and overview["profiles"][0]["preferences"]["empty"], f"{code} {overview}")
    code, bad_sport = s.dsports("PUT", f"/api/customers/me/profiles/{owner_id}/preferences", alice, {"sports": ["Quidditch"]})
    check("5. a sport the catalogue does not sell is rejected", code == 400, f"{code} {bad_sport}")
    code, saved = s.dsports("PUT", f"/api/customers/me/profiles/{owner_id}/preferences", alice, {
        "sports": ["tennis"], "favoriteBrands": ["Head"], "usageLevel": "intermediate", "priceMin": 500, "priceMax": 12000, "sizes": {"shoe": "UK 8"}, "personalizationEnabled": True})
    check("5. sports and budget preferences are saved (sport stored in the catalogue's spelling)", code == 200 and saved["preferences"]["sports"] == ["Tennis"] and saved["preferences"]["priceMax"] == 12000, f"{code} {saved}")
    code, reloaded = s.dsports("GET", f"/api/customers/me/profiles/{owner_id}", alice)
    check("5. the preferences persist after reload", code == 200 and reloaded["preferences"]["favoriteBrands"] == ["Head"] and reloaded["preferences"]["usageLevel"] == "INTERMEDIATE")
    code, steal = s.dsports("GET", f"/api/customers/me/profiles/{owner_id}", bob)
    code_w, steal_w = s.dsports("PUT", f"/api/customers/me/profiles/{owner_id}/preferences", bob, {"sports": ["Cricket"]})
    check("5. another customer cannot read or change the profile (404)", code == 404 and code_w == 404, f"{code} {code_w}")
    code, kid = s.dsports("POST", "/api/customers/me/family-profiles", alice, {"name": "Meera", "relationship": "Daughter", "ageBand": "TEEN_13_17",
                          "preferences": {"sports": ["Cricket"], "priceMax": 9000}})
    check("5. a family profile is created with an age band (no date of birth)", code == 201 and kid["kind"] == "FAMILY" and kid["ageBand"] == "TEEN_13_17" and "dateOfBirth" not in kid, f"{code} {kid}")
    kid_id = kid.get("id", "")
    code, child = s.dsports("POST", "/api/customers/me/family-profiles", alice, {"name": "Aarav", "relationship": "Son", "ageBand": "CHILD_8_12",
                            "preferences": {"sports": ["Football"], "priceMax": 9000}})
    child_id = child.get("id", "")
    code, ov2 = s.dsports("GET", "/api/customers/me/profiles", alice)
    check("5. the account lists its own profile and both family members, the owner marked as OWNER", code == 200 and [p["kind"] for p in ov2["profiles"]] == ["OWNER", "FAMILY", "FAMILY"] and ov2["activeProfileId"] == owner_id, f"{code}")

    # ============================================================== 6. home-page recommendations reflect the preferences (and the selected profile)
    code, own_reco = reco(alice, scenario="PERSONALIZED", limit=6)
    items = own_reco.get("items", []) if code == 200 else []
    check("6. personalised results for the owner profile are Tennis, within the saved price range", code == 200 and items and all(i["sport"] == "Tennis" and i["price"] <= 12000 for i in items),
          f"{code} {[(i['name'], i['sport'], i['price']) for i in items]}")
    check("6. the explanations name real ranking signals (sport/brand in the profile)", items and any("sport in your profile" in r for r in items[0]["reasons"]), f"{items[:1]}")
    check("6. the response says personalisation was applied and how many candidates were evaluated", own_reco.get("personalized") is True and own_reco["meta"]["candidates_evaluated"] > 0 and own_reco["meta"]["llm_tokens"] == 0,
          f"{own_reco.get('meta')}")
    code, sel = s.dsports("PUT", "/api/customers/me/profiles/active", alice, {"profileId": kid_id})
    code, kid_reco = reco(alice, scenario="PERSONALIZED", limit=6)
    kitems = kid_reco.get("items", []) if code == 200 else []
    check("6. switching to the family profile changes the results (Cricket, within its budget)", code == 200 and kitems and all(i["sport"] == "Cricket" and i["price"] <= 9000 for i in kitems) and
          {i["product_id"] for i in kitems}.isdisjoint({i["product_id"] for i in items}), f"{[(i['name'], i['sport'], i['price']) for i in kitems]}")
    check("6. the family profile does not use the account holder's purchase history", "Purchase history is not used" in (kid_reco.get("personalizationNote") or ""), f"{kid_reco.get('personalizationNote')}")
    s.dsports("PUT", "/api/customers/me/profiles/active", alice, {"profileId": child_id})
    code, child_reco = reco(alice, scenario="PERSONALIZED", limit=6)
    citems_child = child_reco.get("items", []) if code == 200 else []
    check("6. a child's profile (Football) never shows adult-only products; junior products are shown", code == 200 and citems_child and all(i["attributes"].get("ageGroup") != "adult" for i in citems_child) and
          all(i["sport"] == "Football" for i in citems_child), f"{code} {[(i['name'], i['attributes'].get('ageGroup')) for i in citems_child]}")
    s.dsports("PUT", f"/api/customers/me/family-profiles/{child_id}", alice, {"name": "Aarav", "relationship": "Son", "ageBand": "CHILD_8_12"})
    s.dsports("PUT", f"/api/customers/me/profiles/{child_id}/preferences", alice, {"sports": ["Cricket"], "priceMax": 9000})
    code, empty_child = reco(alice, scenario="PERSONALIZED", limit=6)
    check("6. a child's Cricket profile is honestly empty when the catalogue has no junior Cricket products", code == 200 and empty_child["items"] == [] and "suitable for juniors" in (empty_child.get("message") or ""),
          f"{code} {empty_child.get('message')}")
    code, foreign = reco(bob, scenario="PERSONALIZED", profileId=owner_id)
    check("6. another customer's profile id is a 404 in the recommendation API", code == 404, f"{code} {foreign}")
    s.dsports("PUT", "/api/customers/me/profiles/active", alice, {"profileId": owner_id})
    code, blank = reco(bob, scenario="PERSONALIZED", limit=4)
    check("6. a customer with no preferences gets a labelled fallback, not fake personalisation", code == 200 and blank.get("personalized") is False and "Add what you play" in (blank.get("message") or ""), f"{code} {blank.get('message')}")

    # ============================================================== 7. a ~Rs 10,000 reference product: complementary recommendations with the default 20% ceiling
    code, ref = s.dsports("GET", f"/api/prices?productId={HEAD_RACQUET}")
    ref_price = (ref[0] if isinstance(ref, list) else ref)["sellingPrice"]
    code, comp = reco(alice, referenceProductId=HEAD_RACQUET, scenario="COMPLEMENTARY", limit=6)
    ceiling = round(ref_price * 0.20, 2)
    applied = comp.get("budget", {}).get("applied", {})
    check(f"7. the reference ({ref_price:,.0f}) uses the default 20% ceiling ({ceiling:,.2f}) and the response says so", code == 200 and applied.get("source") == "policy" and applied.get("high") == ceiling and applied.get("pct") == 0.2,
          f"{code} {applied}")
    citems = comp.get("items", [])
    check("7. every complementary item is in stock, same sport, and at or below the ceiling", citems and all(i["price"] <= ceiling and i["sport"] == "Tennis" and i["in_stock"] and i["product_id"] != HEAD_RACQUET for i in citems),
          f"{[(i['name'], i['price']) for i in citems]}")
    check("7. no premium product is promoted as a complement", all(i["price"] < ref_price for i in citems))
    check("7. prices, original price/discount and availability come from DSports data", all(("original_price" in i and "discount_pct" in i and i.get("availability")) for i in citems) and
          all((i["discount_pct"] is None) == (i["original_price"] is None) for i in citems), f"{citems[:1]}")
    code, live = s.dsports("GET", f"/api/prices?productId={citems[0]['product_id']}") if citems else (0, [])
    live_price = (live[0] if isinstance(live, list) else live)["sellingPrice"] if citems else None
    check("7. the displayed price equals the live pricing API", citems and live_price == citems[0]["price"], f"{live_price} vs {citems[0]['price'] if citems else None}")
    check("7. the ceiling percentage is configuration (AI_RECO_COMPLEMENTARY_CEILING_PCT), reported in every response", comp["budget"]["complementary_ceiling_pct"] == 0.2)

    # ============================================================== 8. explicit budget and premium upgrade
    code, tight = reco(alice, referenceProductId=HEAD_RACQUET, scenario="COMPLEMENTARY", budget=500, limit=6)
    tapplied = tight.get("budget", {}).get("applied", {})
    check("8. an explicit budget (Rs 500) overrides the 20% rule and is never widened", code == 200 and tapplied.get("source") == "explicit" and tapplied.get("high") == 500 and tight["budget"]["fallback_used"] is False and
          all(i["price"] <= 500 for i in tight["items"]), f"{tapplied} {[(i['name'], i['price']) for i in tight['items']]}")
    code, nothing = reco(alice, referenceProductId=HEAD_RACQUET, scenario="COMPLEMENTARY", budget=5, limit=6)
    check("8. an impossible budget returns no products and a message (no invented items)", code == 200 and nothing["items"] == [] and nothing.get("message"), f"{nothing.get('message')}")
    code, up = reco(alice, referenceProductId=SS_BAT, scenario="UPGRADE", limit=6)
    uitems = up.get("items", [])
    check("8. premium alternatives (only when asked) are dearer comparable bats within the upgrade band", code == 200 and uitems and all(i["price"] > 7499 and i["category"] == "Bat" for i in uitems) and
          up["budget"]["applied"]["high"] == round(7499 * 2.0, 2), f"{[(i['name'], i['price']) for i in uitems]}")
    code, plain = reco(alice, referenceProductId=SS_BAT, limit=6)
    check("8. the default scenario for a product never includes premium bats", all(i["category"] != "Bat" for i in plain.get("items", [])), f"{[(i['name'], i['price']) for i in plain.get('items', [])]}")
    code, sim = reco(bob, referenceProductId=SS_BAT, scenario="SIMILAR", limit=6)      # bob owns no bat, so the comparable MRF bat is eligible
    check("8. similar products stay within +/-25% of the reference price and in the same category", code == 200 and all(5624.25 <= i["price"] <= 9373.75 and i["category"] == "Bat" for i in sim["items"]) and sim["items"] != [],
          f"{[(i['name'], i['price']) for i in sim.get('items', [])]}")
    code, upb = reco(alice, referenceProductId=SS_BAT, scenario="UPGRADE", budget=9000, limit=6)
    check("8. an explicit budget also caps a premium request", code == 200 and all(7499 < i["price"] <= 9000 for i in upb["items"]), f"{[(i['name'], i['price']) for i in upb.get('items', [])]}")
    # frequently bought together: needs real evidence -> create it with two real orders containing both products
    ball_pairs = int(s.sql(f"select count(distinct a.order_id) from order_items a join order_items b on b.order_id=a.order_id and b.product_id<>a.product_id "
                           f"join orders o on o.id=a.order_id and o.status<>'CANCELLED' where a.product_id='{MRF_BAT}' and b.product_id='{BALL_KOOK}'"))
    for who in (alice, bob):                                    # two real orders, each containing both products, are the evidence
        buy_lines(who, [(MRF_BAT, 1), (BALL_KOOK, 1)])
    _, carol = s.register("mf-carol")                           # owns neither product, so the pair is eligible for her
    code, fbt = reco(carol, referenceProductId=MRF_BAT, scenario="FREQUENTLY_BOUGHT_TOGETHER")
    ball = next((i for i in fbt.get("items", []) if i["product_id"] == BALL_KOOK), None)
    expected = ball_pairs + 2
    check(f"8. frequently-bought-together appears with real evidence and states the true order count ({expected})",
          code == 200 and ball is not None and any(f"in {expected} orders" in r for r in ball["reasons"]), f"{code} {fbt.get('evidence')} {ball and ball['reasons']}")
    code, guest_fbt = reco(None, referenceProductId=MRF_BAT, scenario="FREQUENTLY_BOUGHT_TOGETHER")
    check("8. a guest gets no purchase-pattern data (sign-in hint)", code == 200 and guest_fbt["items"] == [] and "Sign in" in (guest_fbt.get("message") or ""), f"{code}")

    # ============================================================== 9. regression: shopping, cart (recommended product), checkout, orders, auth still work
    pick = citems[0]["product_id"] if citems else BALL_KOOK
    code, added = s.dsports("POST", "/api/v1/cart/items", bob, {"productId": pick, "quantity": 1})
    check("9. a recommended product is added through the existing cart API", code == 200, f"{code} {added}")
    code, cart = s.dsports("GET", "/api/v1/cart", bob)
    check("9. the cart holds the recommended product at the recommended price", code == 200 and any(i["productId"] == pick and i["unitPrice"] == (citems[0]["price"] if citems else i["unitPrice"]) for i in cart["items"]), f"{code}")
    o = s.buy(alice, MRF_BAT, 1)
    p = s.pay(alice, o)
    code, orders = s.dsports("GET", "/api/v1/orders", alice)
    check("9. checkout, payment and the order list still work end to end", code == 200 and any(x["id"] == o["id"] for x in orders) and p.get("status") in ("SUCCESS", "CAPTURED", "COMPLETED"), f"{p.get('status')}")
    code, profile = s.dsports("GET", "/api/customers/me", alice)
    code_r, bad_login = s.dsports("POST", "/api/auth/login", body={"email": alice_email, "password": "wrong-password"})
    check("9. authentication still works (profile loads; a wrong password is refused)", code == 200 and profile["email"] == alice_email and code_r in (400, 401), f"{code} {code_r}")
    code, api_docs = s.dsports("GET", "/api-docs")
    paths = json.dumps(api_docs.get("paths", {})) if isinstance(api_docs, dict) else ""
    check("9. the new endpoints are documented in OpenAPI", all(x in paths for x in ("/api/customers/me/profiles", "/api/customers/me/family-profiles", "/api/v1/insights/co-purchases", "/priority")), "")

    # cleanup of the family profile created here (hard delete = the retention rule)
    code, _ = s.dsports("DELETE", f"/api/customers/me/family-profiles/{child_id}", alice)           # the active profile: deleting it falls back to the owner
    left = s.sql(f"select count(*) from shopper_profiles where id='{child_id}'")
    code_a, back = s.dsports("GET", "/api/customers/me/profiles/active", alice)
    check("5. deleting the active family profile removes its data and the owner becomes active again", code == 204 and left == "0" and code_a == 200 and back["id"] == owner_id, f"{code} {left} {code_a}")
    s.dsports("DELETE", f"/api/customers/me/family-profiles/{kid_id}", alice)

    failed = [r for r in RESULTS if not r[1]]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
