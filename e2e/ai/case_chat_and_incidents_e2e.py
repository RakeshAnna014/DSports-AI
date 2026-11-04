"""Real-stack check of (1) the case-linked support chat (real Gemini) and (2) Engineering AI near-live refresh, incident ranking, investigation and coding prompt on REAL logs.

Creates two customers and one ticket (subject starts with "E2E case chat"). No mocks. Run: python e2e/ai/case_chat_and_incidents_e2e.py"""
from __future__ import annotations

import sys
import uuid

sys.path.insert(0, __file__.rsplit("e2e", 1)[0] + "e2e/ai")
import stack  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {str(extra)[:400]}"))
    if not cond:
        FAILED.append(name)


def main() -> int:
    _, cust = stack.register("casechat")
    _, other = stack.register("casechat2")
    adm_email, _ = stack.register("engadm")
    admin = stack.make_admin(adm_email)

    s, created = stack.dsports("POST", "/api/v1/support/cases", cust, {"category": "GENERAL", "subject": "E2E case chat question", "description": "I have a question about my account setup."},
                               {"Idempotency-Key": str(uuid.uuid4())})
    check("ticket created", s in (200, 201), (s, created))
    ref = (created.get("supportCase") or created)["caseNumber"]

    s, chat = stack.ai("POST", "/v1/chat", cust, {"message": "what is the status of this case?", "caseRef": ref})
    check("linked chat answers", s == 200 and chat["message"], (s, chat))
    check("the case number shown is the real one", ref in str(chat), chat.get("message"))
    s, conv = stack.ai("GET", f"/v1/support/conversation?caseRef={ref}", cust)
    check("conversation for the case can be resumed", s == 200, (s, conv))
    s, _ = stack.ai("POST", "/v1/chat", other, {"message": "what is the status of this case?", "caseRef": ref})
    check("another customer cannot link to this case", s in (403, 404) or ref not in str(_), (s, _))
    s, tickets = stack.dsports("GET", "/api/v1/support/cases", cust)
    check("no second ticket was created by chatting", s == 200 and sum(1 for t in tickets if t["caseNumber"] == ref) == 1 and len(tickets) == 1, tickets)

    # ---- Engineering AI on real logs (ADMIN only)
    s, _ = stack.ai("GET", "/v1/engineering/incidents", cust)
    check("customer cannot read incidents", s == 403, s)
    s, ref_result = stack.ai("POST", "/v1/engineering/refresh", admin)
    check("near-live refresh reads real sources", s == 200 and "sources" in ref_result, (s, ref_result))
    s, src = stack.ai("GET", "/v1/engineering/sources", admin)
    check("sources report freshness", s == 200 and all("freshness" in x for x in src["sources"]), (s, src))
    s, inc = stack.ai("GET", "/v1/engineering/incidents?hours=720&origin=real&limit=10", admin)
    check("incident list responds with pagination", s == 200 and "incidents" in inc and len(inc["incidents"]) <= 10, (s, inc))
    items = inc.get("incidents", []) if s == 200 else []
    print(f"INFO real incident groups in window: {len(items)}")
    if items:
        first = items[0]
        body = {"signature": first["signature"], "since": inc["window"]["since"], "until": inc["window"]["until"], "origin": "real"}
        s, rep = stack.ai("POST", "/v1/engineering/incidents/investigate", admin, body)
        check("investigation returns hypotheses only", s == 200 and all(h["status"] == "HYPOTHESIS" for h in rep["hypotheses"]), (s, rep))
        s, pr = stack.ai("POST", "/v1/engineering/incidents/prompt", admin, body)
        check("coding prompt generated and says it fixes nothing", s == 200 and pr["prompt"] and any("does not fix" in w for w in pr["warnings"]), (s, pr))
    else:
        print("INFO no real error groups to investigate in the window (that is a valid result, not a failure)")

    print("\nALL PASS" if not FAILED else f"\n{len(FAILED)} FAILED: {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
