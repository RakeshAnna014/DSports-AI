"""AI assistance for support STAFF on one ticket: a tailored resolution plan, a safe customer-reply draft, and an append-only record of what was suggested and done.

What this module does NOT do: it never resolves, closes, assigns, re-prioritises, replies, refunds or changes anything. Every action in a plan is a button that the
staff member confirms in the console, which then calls the existing backend endpoint with the staff member's own token (the backend checks the role, the lifecycle and
the version the person was looking at). This module only records SUGGESTED / ATTEMPTED / SUCCEEDED / FAILED so that "the AI suggested a fix", "an action was attempted",
"it succeeded" and "the ticket is resolved" stay four different statements.

Evidence is the ticket (its backend-verified snapshot and attached evidence), live facts the staff member's ROLE may read, approved knowledge, and a derived engineering
signal. Customer text is data, never instructions: no model is called here, so nothing a customer wrote can steer the plan.
"""
from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from app.errors import ApiException
from app.guard.untrusted import sanitize_untrusted
from app.support import playbooks as pb
from app.tools.dsports_client import ToolError
from app.tools.support_tools import normalize_case_ref

log = logging.getLogger(__name__)
STAFF_ROLES = {"SUPPORT_EXECUTIVE", "ADMIN", "ROLE_SUPPORT_EXECUTIVE", "ROLE_ADMIN"}
NEXT_STATUS = {"OPEN": ["INVESTIGATING", "WAITING_FOR_CUSTOMER"], "INVESTIGATING": ["WAITING_FOR_CUSTOMER", "WAITING_FOR_EXTERNAL_PARTY"],
               "WAITING_FOR_CUSTOMER": ["INVESTIGATING"], "WAITING_FOR_EXTERNAL_PARTY": ["INVESTIGATING"], "RESOLVED": ["INVESTIGATING"]}
TECH_CATEGORIES = {"PAYMENT_ISSUE", "DUPLICATE_PAYMENT", "TECHNICAL_ISSUE", "ORDER_ISSUE"}
STATES = ("SUGGESTED", "ATTEMPTED", "SUCCEEDED", "FAILED")


def is_staff(principal) -> bool:
    return bool(STAFF_ROLES & set(principal.roles))


def _clean(text: Any, limit: int) -> str:
    return sanitize_untrusted(str(text or ""), limit)[0]


def _fact(statement: str, source: str, ref: str | None = None) -> dict[str, Any]:
    return {"statement": statement, "source": source, "ref": ref}


def _aid(case_ref: str, kind: str, params: dict[str, Any]) -> str:
    return hashlib.sha1(f"{case_ref}|{kind}|{json.dumps(params, sort_keys=True, default=str)}".encode()).hexdigest()[:12]


# ---------------------------------------------------------------------------------------------------- reading
async def read_case(services, principal, ref: str) -> dict[str, Any]:
    clean = normalize_case_ref(ref)
    if clean is None:
        raise ApiException(400, "VALIDATION_ERROR", "That is not a valid case number")
    try:
        return await services.dsports.get(f"/api/v1/support/staff/cases/{clean}", token=principal.token)
    except ToolError as e:
        status = 404 if e.code in ("NOT_FOUND", "CASE_NOT_FOUND") else 403 if e.code == "FORBIDDEN" else 502 if e.code.startswith("UPSTREAM") else 400
        raise ApiException(status, e.code, e.message) from None


async def _live(services, principal, case: dict[str, Any]) -> dict[str, Any]:
    """Current order and shipment facts. The backend allows these reads to ADMIN only, so a support executive gets an explicit 'not available' instead of a guess."""
    order_id = case.get("orderId")
    if not order_id:
        return {"available": False, "reason": "This ticket is not linked to an order."}
    if "ADMIN" not in principal.roles and "ROLE_ADMIN" not in principal.roles:
        return {"available": False, "reason": "Live order and shipment data can only be read by an administrator; the snapshot taken when the ticket was opened is shown instead."}
    out: dict[str, Any] = {"available": True}
    try:
        order = await services.dsports.get(f"/api/v1/admin/orders/{order_id}", token=principal.token)
        out["order"] = {"number": order.get("orderNumber"), "status": order.get("status"), "grandTotal": order.get("grandTotal"), "currency": order.get("currency"),
                        "items": [{"name": i.get("productName"), "quantity": i.get("quantity")} for i in (order.get("items") or [])][:20]}
    except ToolError as e:
        out["order_error"] = e.message
    try:
        ship = await services.dsports.get(f"/api/v1/shipments/by-order/{order_id}", token=principal.token, allow_404=True)
        if ship:
            s = ship.get("shipment") or {}
            events = ship.get("events") or []
            out["shipment"] = {"status": s.get("status"), "tracking": s.get("trackingNumber"), "delivered_at": s.get("deliveredAt"),
                               "last_events": [{"status": e.get("status"), "at": e.get("eventTime") or e.get("at"), "location": e.get("location")} for e in events[-3:]]}
        else:
            out["shipment"] = None
    except ToolError as e:
        out["shipment_error"] = e.message
    return out


async def _knowledge(services, case: dict[str, Any], playbook: dict[str, Any]) -> dict[str, Any]:
    retriever = getattr(services, "retriever", None)
    if retriever is None:
        return {"available": False, "reason": "The knowledge base is not available (the AI database is not configured).", "sources": []}
    query = f"{playbook['title']} {case.get('category', '')}".replace("_", " ")
    out: list[dict[str, Any]] = []
    try:
        for internal, doc_types in (("sup_", None), (False, ["policy", "procedure", "faq", "warranty"])):
            r = await retriever.search_chunks(query, document_types=doc_types, limit=2, internal=internal)
            out += [{"title": h.payload.get("title"), "source": h.payload.get("source"), "chunk_id": h.id, "snippet": _clean(h.payload.get("content"), 220),
                     "internal": bool(internal), "document_type": h.payload.get("document_type")} for h in r.hits]
    except Exception as e:  # noqa: BLE001 - knowledge is supporting evidence; its absence must not break the plan
        log.info("staff knowledge lookup failed: %s", type(e).__name__)
        return {"available": False, "reason": "The knowledge base could not be searched right now.", "sources": []}
    return {"available": True, "sources": out[:4], "note": "SAMPLE policy and procedure documents (demo content): they are not an official SLA or policy commitment."}


async def _engineering_signal(services, case: dict[str, Any]) -> dict[str, Any]:
    repo = getattr(services, "engineering", None)
    if repo is None or case.get("category") not in TECH_CATEGORIES:
        return {"available": False, "reason": "Not applicable to this category." if repo is not None else "Engineering log analysis is not available."}
    try:
        from app.engineering.analysis import build_clusters
        from app.engineering.incidents import reference_of
        from app.engineering.repository import LogFilter
        from app.engineering.safe import safe_text
        created = datetime.fromisoformat(str(case["createdAt"]).replace("Z", "+00:00"))
        now = datetime.now(timezone.utc)
        since, until = created - timedelta(hours=6), min(created + timedelta(hours=6), now)
        if until <= since:
            return {"available": True, "groups": [], "note": "No window to check."}
        rows = await repo.clusters(LogFilter(since, until, ("backend", "ai-service"), "ERROR", "real"), 5)
        groups = [{"reference": reference_of(c.signature), "title": safe_text(c.text, 160)[0], "category": c.category, "count": c.count, "services": c.services,
                   "first_seen": c.first_seen.isoformat(), "last_seen": c.last_seen.isoformat()} for c in build_clusters(rows)[:3]]
        return {"available": True, "groups": groups, "window": {"since": since.isoformat(), "until": until.isoformat()},
                "note": "Derived error groups from REAL logs within 6 hours of the ticket (synthetic data is excluded). Timing alone does not show a link."}
    except Exception as e:  # noqa: BLE001
        log.info("engineering signal failed: %s", type(e).__name__)
        return {"available": False, "reason": "The log store could not be read right now."}


# ---------------------------------------------------------------------------------------------------- the plan
def _draft(case: dict[str, Any], live: dict[str, Any], status_facts: list[str]) -> dict[str, Any]:
    """A reply the staff member can edit and send. Only verified facts, no timelines or promises, never customer text, internal notes or log content."""
    number = case.get("caseNumber")
    order = case.get("orderNumber")
    lines = ["Hello,", ""]
    basis: list[str] = []
    cat = case.get("category")
    live_ship = (live.get("shipment") or {}).get("status") if live.get("available") else None
    snap_ship = case.get("shipmentStatusAtCreation")
    ship = live_ship or snap_ship
    if order and cat in pb.DELIVERY:
        if ship == "DELIVERED":
            lines.append(f"Our records show that order {order} was marked as delivered. We understand it has not reached you, and we are checking the delivery details with the delivery partner.")
            basis.append(f"shipment status {ship} ({'live' if live_ship else 'when the ticket was opened'})")
        elif ship:
            lines.append(f"Order {order} is currently shown as {ship.replace('_', ' ').lower()}. We are checking the latest tracking information for you.")
            basis.append(f"shipment status {ship}")
        else:
            lines.append(f"We are checking the delivery details of order {order} for you.")
    elif cat in pb.PAYMENT:
        lines.append(f"We are checking the payment records for {'order ' + order if order else 'your order'}. We will confirm what the payment records show.")
    elif cat in pb.MONEY_BACK:
        lines.append(f"We are looking at your request about {'order ' + order if order else 'your order'} and the related return or refund records.")
    elif cat in pb.PRODUCT:
        lines.append(f"We are sorry about the problem with your product{' from order ' + order if order else ''}. We are reviewing it against our return and warranty information.")
    elif cat == "TECHNICAL_ISSUE":
        lines.append("Thank you for reporting this. We are looking into the problem you described.")
    else:
        lines.append(f"We are looking into your request ({number}).")
    missing = [m for m in (case.get("missingInformation") or []) if m]
    if missing and case.get("status") != "RESOLVED":
        lines += ["", "To help us, could you tell us:", *[f"- {_clean(m, 160)}" for m in missing[:4]]]
        basis.append("information missing on the ticket")
    lines += ["", "We will update this ticket as soon as we have verified information.", "", "DSports support"]
    return {"text": "\n".join(lines), "basis": basis or ["the ticket's order and category"],
            "never_includes": "internal notes, stack traces or log text, other customers' data, refund or delivery promises, response-time commitments (no SLA is defined)",
            "review_required": True}


async def build_plan(services, principal, ref: str, pool) -> dict[str, Any]:
    case = await read_case(services, principal, ref)
    number = case["caseNumber"]
    cat = case.get("category") or "GENERAL"
    playbook = pb.PLAYBOOKS.get(cat) or pb.PLAYBOOKS["GENERAL"]
    status = case.get("status")
    resolved = status == "RESOLVED"
    live = await _live(services, principal, case)
    knowledge = await _knowledge(services, case, playbook)
    signal = await _engineering_signal(services, case)

    # ---- verified facts and what is still unknown
    known: list[dict[str, Any]] = [
        _fact(f"Ticket {number} is {status}, priority {case.get('priority')}" + (f" ({'; '.join(_clean(r, 120) for r in (case.get('priorityReasons') or [])[:3])})" if case.get("priorityReasons") else "") + ".", "TICKET", number),
        _fact(f"Opened through {'the AI assistant' if case.get('source') == 'AI_ASSISTANT' else 'the website form'} on {str(case.get('createdAt'))[:16].replace('T', ' ')} UTC; category {cat}.", "TICKET", number)]
    esc = (case.get("escalation") or {}).get("state", "NONE")
    if esc == "REQUESTED":
        known.append(_fact("The customer asked for a person; nobody has accepted the escalation yet (a request is not an accepted escalation).", "TICKET", number))
    elif esc == "ACCEPTED":
        known.append(_fact("A support executive has accepted the escalation.", "TICKET", number))
    known.append(_fact("Assigned to a support executive." if case.get("assignedTo") else "Not assigned to anyone yet.", "TICKET", number))
    if case.get("reopenCount"):
        known.append(_fact(f"This ticket has been reopened {case['reopenCount']} time(s).", "TICKET", number))
    if case.get("orderNumber"):
        known.append(_fact(f"Order {case['orderNumber']}: status when the ticket was opened was {case.get('orderStatusAtCreation') or 'unknown'}"
                           f"{', shipment ' + case['shipmentStatusAtCreation'] if case.get('shipmentStatusAtCreation') else ''}"
                           f"{', total ' + str(case['orderTotal']) + ' ' + str(case.get('currency') or '') if case.get('orderTotal') is not None else ''}.", "BACKEND_SNAPSHOT", case["orderNumber"]))
    for ev in (case.get("evidence") or [])[:8]:
        known.append(_fact(_clean(ev.get("summary"), 200), ev.get("source") or "TICKET_EVIDENCE", ev.get("ref")))
    changed: list[str] = []
    if live.get("available"):
        o, s = live.get("order") or {}, live.get("shipment") or {}
        if o:
            known.append(_fact(f"Now: order {o.get('number')} is {o.get('status')}.", "BACKEND_LIVE", o.get("number")))
            if case.get("orderStatusAtCreation") and o.get("status") and o["status"] != case["orderStatusAtCreation"]:
                changed.append(f"Order status changed from {case['orderStatusAtCreation']} to {o['status']} since the ticket was opened.")
        if s:
            known.append(_fact(f"Now: shipment {s.get('tracking') or ''} is {s.get('status')}.".replace("shipment  is", "shipment is"), "BACKEND_LIVE", s.get("tracking")))
            if case.get("shipmentStatusAtCreation") and s.get("status") and s["status"] != case["shipmentStatusAtCreation"]:
                changed.append(f"Shipment status changed from {case['shipmentStatusAtCreation']} to {s['status']} since the ticket was opened.")
    known += [_fact(c, "BACKEND_LIVE") for c in changed]
    uncertain = [_clean(m, 200) for m in (case.get("missingInformation") or [])[:6]]
    if not live.get("available"):
        uncertain.append(live.get("reason", "Live data was not read."))
    uncertain += [n for n in playbook["needs"]]

    # ---- likely cause: hypotheses only, ranked by what the facts support
    hyps: list[dict[str, Any]] = []
    for i, (statement, confirm) in enumerate(pb.CAUSE_HINTS.get(cat, [])):
        confidence = "LOW"
        basis = "the ticket category"
        if cat == "MISSING_DELIVERY" and (case.get("shipmentStatusAtCreation") == "DELIVERED" or (live.get("shipment") or {}).get("status") == "DELIVERED") and i == 0:
            confidence, basis = "MEDIUM", "the shipment is recorded as delivered while the customer reports it missing"
        if cat in ("PAYMENT_ISSUE", "TECHNICAL_ISSUE") and i == 1 and signal.get("groups"):
            confidence, basis = "MEDIUM", "a real error group was recorded within 6 hours of the ticket"
        hyps.append({"status": "HYPOTHESIS", "statement": statement, "confidence": confidence, "basis": basis, "to_confirm": confirm})
    hyps.sort(key=lambda h: 0 if h["confidence"] == "MEDIUM" else 1)
    if not hyps:
        hyps.append({"status": "HYPOTHESIS", "statement": "There is not enough evidence on the ticket to suggest a cause.", "confidence": "LOW", "basis": "no matching pattern", "to_confirm": "the information listed under 'uncertain'"})

    evidence: list[dict[str, Any]] = [{"kind": "TICKET_EVIDENCE", "ref": e.get("ref"), "summary": _clean(e.get("summary"), 200), "source": e.get("source")} for e in (case.get("evidence") or [])[:8]]
    evidence += [{"kind": "KNOWLEDGE", "ref": s["chunk_id"], "summary": f"{s['title']}: {s['snippet']}", "source": "INTERNAL_KB" if s["internal"] else "POLICY_KB"} for s in knowledge.get("sources", [])]
    evidence += [{"kind": "ENGINEERING_SIGNAL", "ref": g["reference"], "summary": f"{g['count']} x {g['title']} ({g['category'].lower()})", "source": "REAL_LOGS_DERIVED"} for g in signal.get("groups", [])]
    if live.get("available"):
        evidence.append({"kind": "LIVE_CHECK", "ref": None, "summary": "Order and shipment read now with the staff member's own role.", "source": "BACKEND_LIVE"})

    # ---- steps with the operation each one can use
    steps = [{"order": i, "text": t} for i, t in enumerate(playbook["steps"], 1)]
    if signal.get("groups"):
        steps.append({"order": len(steps) + 1, "text": f"A real error group ({signal['groups'][0]['reference']}) was recorded near the ticket time: record it as a candidate in an internal note and ask engineering to look (it is not confirmed as the cause)."})

    # ---- actions (suggestions; each needs the staff member's confirmation and runs against an existing endpoint)
    actions: list[dict[str, Any]] = []
    version = case.get("version")
    draft = _draft(case, live, [])

    def add(kind: str, label: str, body: dict[str, Any], params: dict[str, Any], confirm: str, **extra: Any) -> None:
        actions.append({"id": _aid(number, kind, params), "kind": kind, "label": label, "operation": pb.OPERATIONS[kind].replace("{ref}", number), "body": body,
                        "confirmation": confirm, "state": "SUGGESTED", **extra})

    if not resolved and not case.get("assignedTo"):
        add("ASSIGN_SELF", "Take this ticket", {"takeIt": True, "expectedVersion": version}, {"takeIt": True},
            "You will be recorded as the person handling this ticket" + (" and the customer's request for a person is accepted." if esc == "REQUESTED" else "."))
    if not resolved:
        add("REPLY_CUSTOMER", "Send the drafted reply to the customer", {"body": draft["text"], "expectedVersion": version}, {"draft": hashlib.sha1(draft["text"].encode()).hexdigest()[:10]},
            "The customer will see this message on their ticket. Review and edit it first.", editable_text=True, draft_basis=draft["basis"])
        for target in NEXT_STATUS.get(status, []):
            if target == "WAITING_FOR_CUSTOMER" and (case.get("missingInformation") or []):
                add("SET_STATUS", "Mark as waiting for the customer", {"status": target, "note": "Waiting for the information requested in the reply.", "expectedVersion": version}, {"to": target},
                    "The ticket status changes to WAITING_FOR_CUSTOMER and the change is recorded in its history.")
    if signal.get("groups"):
        g0 = signal["groups"][0]
        add("ADD_NOTE", f"Link {g0['reference']} as a candidate (internal note)",
            {"body": f"AI-assisted note: error group {g0['reference']} ({g0['category'].lower()}, {g0['count']} records) was recorded near the time of this ticket. Candidate only, not confirmed as the cause.", "expectedVersion": version},
            {"signature": g0["reference"]}, "An internal note is added (the customer never sees it).")
    if resolved:
        has_followup = any((m.get("authorType") == "CUSTOMER") and str(m.get("createdAt")) > str(case.get("resolvedAt") or "") for m in (case.get("messages") or []))
        if has_followup:
            add("SET_STATUS", "Reopen: the customer wrote after the resolution", {"status": "INVESTIGATING", "note": "Customer replied after the ticket was resolved.", "expectedVersion": version}, {"to": "INVESTIGATING"},
                "The ticket moves from RESOLVED back to INVESTIGATING.")
    else:
        verify = playbook["needs"]
        add("RESOLVE", "Resolve with an outcome (you decide)", {"outcome": None, "note": "", "evidenceRefs": [], "expectedVersion": version}, {"resolve": True},
            "Resolving records an outcome and a note and closes the ticket for the customer. It does not move money or change an order.",
            outcomes=pb.OUTCOMES, must_verify=verify,
            blocked_reason="Not offered by the assistant as ready: it cannot verify these conditions. An authorised staff member confirms each one, chooses the outcome and writes the note.")

    # ---- escalation
    triggers = list(playbook["escalate"])
    recommended = bool(case.get("safetyRelated") or esc == "REQUESTED" or signal.get("groups"))
    reason = ("Safety-related ticket." if case.get("safetyRelated") else "The customer asked for a person and nobody has accepted yet." if esc == "REQUESTED"
              else "A real error group was recorded near the ticket time." if signal.get("groups") else None)
    plan = {
        "caseRef": number, "generatedAt": datetime.now(timezone.utc).isoformat(), "category": cat, "playbook": playbook["title"], "status": status, "version": version,
        "problem": f"{case.get('subject') and _clean(case['subject'], 160)} (category {cat}" + (f", order {case['orderNumber']}" if case.get("orderNumber") else "") + ").",
        "known": known, "uncertain": list(dict.fromkeys(u for u in uncertain if u)), "likelyCause": {"status": "UNCONFIRMED: ranked hypotheses only", "hypotheses": hyps},
        "evidence": evidence, "steps": steps, "tools": playbook["tools"], "actions": actions, "verification": playbook["needs"], "customerDraft": draft,
        "escalation": {"conditions": triggers, "recommended": recommended, "reason": reason},
        "live": live, "knowledge": knowledge, "engineering": signal,
        "limitations": ["This plan is advice. It does not resolve, close, refund or change anything; every action needs your confirmation and runs through the existing ticket operations.",
                        "Causes are hypotheses. Nothing here confirms a root cause.",
                        "Policy documents are SAMPLE content, and no SLA is defined, so no response-time commitment is made or implied."],
    }
    plan["sensitive"] = bool(playbook.get("sensitive"))
    if pool is not None:
        await record_suggestions(pool, number, principal.customer_hash, actions)
        plan["history"] = await history(pool, number)
    else:
        plan["history"] = []
    return plan


# ---------------------------------------------------------------------------------------------------- the action log
async def record_suggestions(pool, case_ref: str, actor_hash: str, actions: list[dict[str, Any]]) -> None:
    for a in actions:
        exists = await pool.fetchval("SELECT 1 FROM support_ai_actions WHERE case_ref = $1 AND action_id = $2 LIMIT 1", case_ref, a["id"])
        if not exists:
            await pool.execute("INSERT INTO support_ai_actions (case_ref, actor_hash, action_id, kind, label, state) VALUES ($1,$2,$3,$4,$5,'SUGGESTED')", case_ref, actor_hash, a["id"], a["kind"], a["label"])


async def history(pool, case_ref: str) -> list[dict[str, Any]]:
    """Latest state per action, with the whole trail (who did what is a hash, never a name)."""
    rows = await pool.fetch("SELECT at, action_id, kind, label, state, detail, actor_hash FROM support_ai_actions WHERE case_ref = $1 ORDER BY at, id", case_ref)
    by: dict[str, dict[str, Any]] = {}
    for r in rows:
        d = by.setdefault(r["action_id"], {"actionId": r["action_id"], "kind": r["kind"], "label": r["label"], "trail": []})
        detail = json.loads(r["detail"]) if isinstance(r["detail"], str) else dict(r["detail"] or {})
        d["trail"].append({"state": r["state"], "at": r["at"].isoformat(), "detail": detail})
        d["state"] = r["state"]
    return list(by.values())


_ALLOWED = {"ATTEMPTED": {"SUGGESTED", "FAILED"}, "SUCCEEDED": {"ATTEMPTED"}, "FAILED": {"ATTEMPTED"}}


async def record_action(pool, principal, case_ref: str, action_id: str, state: str, detail: dict[str, Any] | None) -> list[dict[str, Any]]:
    ref = normalize_case_ref(case_ref)
    if ref is None:
        raise ApiException(400, "VALIDATION_ERROR", "That is not a valid case number")
    if state not in _ALLOWED:
        raise ApiException(422, "VALIDATION_ERROR", f"state must be one of {sorted(_ALLOWED)}")
    last = await pool.fetchrow("SELECT kind, label, state FROM support_ai_actions WHERE case_ref = $1 AND action_id = $2 ORDER BY at DESC, id DESC LIMIT 1", ref, action_id)
    if last is None:
        raise ApiException(404, "NOT_FOUND", "That action was never suggested for this ticket")          # a made-up action id is refused, not recorded
    if last["state"] not in _ALLOWED[state]:
        raise ApiException(409, "INVALID_TRANSITION", f"An action in state {last['state']} cannot become {state}")
    safe = {k: v for k, v in (detail or {}).items() if k in ("httpStatus", "code", "correlationId", "caseVersion") and isinstance(v, (int, str))}
    safe = {k: (v[:64] if isinstance(v, str) else v) for k, v in safe.items()}
    await pool.execute("INSERT INTO support_ai_actions (case_ref, actor_hash, action_id, kind, label, state, detail) VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb)",
                       ref, principal.customer_hash, action_id, last["kind"], last["label"], state, json.dumps(safe))
    return await history(pool, ref)
