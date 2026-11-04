"""Real-stack check of the staff AI workspace: report, plan, confirmed action trail, insights, feedback and role enforcement.

Creates one customer and one ticket (subject starts with "E2E staff-assist") and an administrator by the same SQL role grant the other E2E scripts use. Nothing is mocked;
the plan and insights are produced by the running AI service against the running backend. Run: python e2e/ai/support_staff_e2e.py"""
from __future__ import annotations

import sys
import uuid

sys.path.insert(0, __file__.rsplit("e2e", 1)[0] + "e2e/ai")
import stack  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {extra}"))
    if not cond:
        FAILED.append(name)


def main() -> int:
    email, cust = stack.register("staffe2e")
    adm_email, _ = stack.register("staffadm")
    admin = stack.make_admin(adm_email)

    s, created = stack.dsports("POST", "/api/v1/support/cases", cust, {"category": "TECHNICAL_ISSUE", "subject": "E2E staff-assist checkout page error",
                               "description": "The checkout page showed an error when I pressed pay."}, {"Idempotency-Key": str(uuid.uuid4())})
    check("customer ticket created", s in (200, 201), (s, created))
    ref = (created.get("supportCase") or created)["caseNumber"]

    s, rep = stack.dsports("GET", "/api/v1/support/staff/report?range=today", admin)
    check("backend report counts the new ticket", s == 200 and rep["createdInPeriod"] >= 1 and rep["definitions"], (s, rep))
    s, _ = stack.dsports("GET", "/api/v1/support/staff/report?range=today", cust)
    check("customer cannot read the report", s == 403, s)
    s, bad = stack.dsports("GET", "/api/v1/support/staff/report?range=custom&from=2026-10-10", admin)
    check("custom range without both dates is rejected", s == 400, (s, bad))

    s, _ = stack.ai("POST", f"/v1/support/staff/cases/{ref}/assist", cust)
    check("customer cannot request a staff plan", s == 403, s)
    s, _ = stack.ai("POST", f"/v1/support/staff/cases/{ref}/assist")
    check("anonymous cannot request a staff plan", s == 401, s)

    s, plan = stack.ai("POST", f"/v1/support/staff/cases/{ref}/assist", admin)
    check("admin gets a plan", s == 200 and plan["caseRef"] == ref and plan["playbook"], (s, plan))
    if s != 200:
        return 1
    check("plan is tailored to the category", plan["category"] == "TECHNICAL_ISSUE" and "technical" in plan["playbook"].lower(), plan["playbook"])
    check("every cause is a hypothesis", all(h["status"] == "HYPOTHESIS" for h in plan["likelyCause"]["hypotheses"]), plan["likelyCause"])
    check("resolve is never pre-filled", all(a["body"]["outcome"] is None for a in plan["actions"] if a["kind"] == "RESOLVE"))
    check("all actions start as SUGGESTED", all(a["state"] == "SUGGESTED" for a in plan["actions"]))
    check("draft keeps the review flag", plan["customerDraft"]["review_required"] is True)

    s, before = stack.dsports("GET", f"/api/v1/support/staff/cases/{ref}", admin)
    check("planning changed nothing on the ticket", s == 200 and before["status"] == "OPEN", before.get("status"))

    reply = next(a for a in plan["actions"] if a["kind"] == "REPLY_CUSTOMER")
    s, h = stack.ai("POST", f"/v1/support/staff/cases/{ref}/actions/{reply['id']}", admin, {"state": "SUCCEEDED"})
    check("cannot record success before an attempt", s == 409, (s, h))
    s, h = stack.ai("POST", f"/v1/support/staff/cases/{ref}/actions/not-offered", admin, {"state": "ATTEMPTED"})
    check("an action the plan never offered is refused", s == 404, (s, h))
    s, h = stack.ai("POST", f"/v1/support/staff/cases/{ref}/actions/{reply['id']}", admin, {"state": "ATTEMPTED"})
    mine = lambda hist: next(x for x in hist["history"] if x["actionId"] == reply["id"])  # noqa: E731
    check("attempt recorded", s == 200 and mine(h)["state"] == "ATTEMPTED", (s, h))
    s, done = stack.dsports("POST", f"/api/v1/support/staff/cases/{ref}/replies", admin, {"body": reply["body"]["body"], "expectedVersion": reply["body"]["expectedVersion"]})
    check("the existing backend endpoint accepted the reply", s == 200, (s, done))
    s, h = stack.ai("POST", f"/v1/support/staff/cases/{ref}/actions/{reply['id']}", admin, {"state": "SUCCEEDED", "detail": {"httpStatus": 200}})
    check("success recorded after the backend answered", s == 200 and [t["state"] for t in mine(h)["trail"]] == ["SUGGESTED", "ATTEMPTED", "SUCCEEDED"], (s, h))
    s, cc = stack.dsports("GET", f"/api/v1/support/cases/{ref}", cust)
    check("the customer sees the reply", s == 200 and any("checking" in m["body"].lower() or "looking" in m["body"].lower() for m in cc["messages"]), cc.get("messages"))
    check("ticket is not resolved by the assistant", cc["status"] != "RESOLVED", cc["status"])

    s, ins = stack.ai("GET", "/v1/support/staff/insights?range=today", admin)
    check("insights respond with labelled items", s == 200 and ins["items"] and {i["label"] for i in ins["items"]} <= {"OBSERVATION", "HYPOTHESIS"}, (s, ins))
    check("insights state the chatbot limitation", "cannot be stated" in ins["chatbot"]["note"] or ins["chatbot"]["resolution_rate"] is not None, ins["chatbot"])
    s, _ = stack.ai("GET", "/v1/support/staff/insights?range=today", cust)
    check("customer cannot read insights", s == 403, s)

    s, chat = stack.ai("POST", "/v1/chat", cust, {"message": "show my cases"})
    if s == 200:
        s2, _ = stack.ai("POST", "/v1/support/feedback", cust, {"conversationId": chat["conversationId"], "outcome": "HELPFUL"})
        check("customer feedback recorded", s2 == 204, s2)
    s3, _ = stack.ai("POST", "/v1/support/feedback", cust, {"conversationId": str(uuid.uuid4()), "outcome": "BOGUS"})
    check("invalid feedback value rejected", s3 == 422, s3)

    print("\nALL PASS" if not FAILED else f"\n{len(FAILED)} FAILED: {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
