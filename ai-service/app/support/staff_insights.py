"""AI support insights: observations over the backend's aggregated ticket report plus the assistant's own recorded feedback.

Every number comes from the backend report or the AI audit table; nothing is estimated. Each item carries its denominator and a label:
OBSERVATION (a measured fact) or HYPOTHESIS (a possible reason, never confirmed). Correlation with engineering errors is labelled as correlation, not cause.
Chatbot effectiveness is reported only from explicit customer feedback; where there is none the response says so instead of inventing a rate.
"""
from __future__ import annotations

from datetime import date, timedelta
from typing import Any

SELF_SERVICEABLE = {"DELAYED_DELIVERY": "order tracking", "REFUND_ISSUE": "refund status", "RETURN_ISSUE": "return eligibility", "ORDER_ISSUE": "order details"}


def previous_period(first: date, last: date) -> tuple[date, date]:
    days = (last - first).days + 1
    return first - timedelta(days=days), first - timedelta(days=1)


def _pct(a: int | float, b: int | float) -> float | None:
    return None if not b else round(100.0 * a / b, 1)


def _item(kind: str, text: str, denominator: str, **extra: Any) -> dict[str, Any]:
    return {"label": kind, "text": text, "denominator": denominator, **extra}


def build_insights(current: dict[str, Any], previous: dict[str, Any] | None, chatbot: dict[str, Any], engineering_groups: list[dict[str, Any]] | None) -> dict[str, Any]:
    items: list[dict[str, Any]] = []
    created = int(current.get("createdInPeriod", 0))
    resolved = int(current.get("resolvedInPeriod", 0))
    period = f"{current.get('from')} to {current.get('to')} ({current.get('timeZone')})"
    if created == 0:
        return {"period": period, "items": [_item("OBSERVATION", "No tickets were created in this period, so there is nothing to analyse.", "0 tickets")],
                "chatbot": chatbot_section(chatbot), "recommendations": [], "limitations": _limits()}

    if previous is not None and int(previous.get("createdInPeriod", 0)) > 0:
        before = int(previous["createdInPeriod"])
        change = round(100.0 * (created - before) / before, 1)
        items.append(_item("OBSERVATION", f"{created} tickets were created, {'up' if change > 0 else 'down' if change < 0 else 'unchanged'} "
                           f"{abs(change)}% from {before} in the previous equal period." if change else f"{created} tickets were created, the same as the previous equal period.",
                           f"{created} vs {before} tickets"))
    elif previous is not None:
        items.append(_item("OBSERVATION", f"{created} tickets were created; the previous equal period had none, so no change can be stated.", f"{created} tickets"))

    cats: dict[str, int] = current.get("createdByCategory") or {}
    prev_cats: dict[str, int] = (previous or {}).get("createdByCategory") or {}
    ranked = sorted(cats.items(), key=lambda kv: (-kv[1], kv[0]))
    if ranked:
        top, n = ranked[0]
        items.append(_item("OBSERVATION", f"The most common category is {top.replace('_', ' ').title()}: {n} of {created} tickets ({_pct(n, created)}%).", f"{n} of {created} tickets"))
    for cat, n in ranked:
        before = prev_cats.get(cat, 0)
        if n >= 3 and n >= 2 * max(before, 1) and previous is not None:
            items.append(_item("OBSERVATION", f"{cat.replace('_', ' ').title()} tickets rose from {before} to {n}.", f"{n} vs {before} tickets"))
            items.append(_item("HYPOTHESIS", "A rise like this may follow a delivery, payment or site problem in the same period; it is not shown to be one.", f"{n} tickets",
                               to_confirm="Compare with the engineering signal and with shipments/payments in the same days."))
            break

    unresolved = int(current.get("createdInPeriodStillUnresolved", 0))
    items.append(_item("OBSERVATION", f"{unresolved} of the {created} tickets created in this period are still unresolved ({_pct(unresolved, created)}%); {resolved} tickets were resolved in the period.",
                       f"{unresolved} of {created} tickets"))
    reopened = int(current.get("reopenedInPeriod", 0))
    if reopened:
        items.append(_item("OBSERVATION", f"{reopened} ticket(s) were reopened in this period, which can mean a resolution did not settle the problem.", f"{reopened} reopened"))
    if int(current.get("unassignedNow", 0)):
        items.append(_item("OBSERVATION", f"{current['unassignedNow']} unresolved ticket(s) have nobody assigned right now.", f"{current['unresolvedNow']} unresolved now"))
    if int(current.get("escalationPendingNow", 0)):
        items.append(_item("OBSERVATION", f"{current['escalationPendingNow']} customer(s) asked for a person and nobody has accepted yet.", f"{current['unresolvedNow']} unresolved now"))

    avoidable = [(c, n) for c, n in ranked if c in SELF_SERVICEABLE and n > 0]
    if avoidable:
        total = sum(n for _, n in avoidable)
        names = ", ".join(f"{c.replace('_', ' ').lower()} ({n})" for c, n in avoidable[:3])
        items.append(_item("HYPOTHESIS", f"{total} of {created} tickets are in categories the assistant can often answer from live data ({names}); some may have been avoidable if the customer had asked the assistant first.",
                           f"{total} of {created} tickets", to_confirm="Whether those customers used the assistant first; the ticket does not record that for web-form tickets."))

    if engineering_groups:
        g = engineering_groups[0]
        items.append(_item("HYPOTHESIS", f"Real error group {g['reference']} ({g['category'].lower()}, {g['count']} records) appeared in the same period. This is a correlation in time, not a shown cause.",
                           f"{g['count']} log records", to_confirm="Match ticket times and affected orders against the error timing."))

    recs = []
    if ranked and ranked[0][1] >= 3:
        recs.append({"label": "HYPOTHESIS", "text": f"If {ranked[0][0].replace('_', ' ').lower()} tickets keep leading, a short staff checklist or a clearer self-service answer for them could reduce handling time.",
                     "basis": f"{ranked[0][1]} of {created} tickets"})
    if reopened and resolved:
        recs.append({"label": "HYPOTHESIS", "text": "Reviewing the reopened tickets' resolution notes may show whether verification before resolving is being skipped.", "basis": f"{reopened} reopened, {resolved} resolved"})
    return {"period": period, "items": items, "chatbot": chatbot_section(chatbot), "recommendations": recs, "limitations": _limits()}


def chatbot_section(c: dict[str, Any]) -> dict[str, Any]:
    fb = int(c.get("feedback_total", 0))
    out: dict[str, Any] = {"conversations_with_support_turns": int(c.get("support_conversations", 0)), "linked_to_a_case": int(c.get("linked_conversations", 0)),
                           "tickets_created_by_assistant_all_time": c.get("created_by_assistant"), "tickets_created_by_web_all_time": c.get("created_by_web"), "feedback": c.get("feedback", {})}
    if fb == 0:
        out["resolution_rate"] = None
        out["note"] = "No customer feedback was recorded in this period, so a resolution rate cannot be stated. A conversation that ended without a ticket is NOT counted as resolved."
    else:
        helpful = int(c.get("feedback", {}).get("HELPFUL", 0))
        out["resolution_rate"] = {"helpful": helpful, "answered_feedback": fb, "percent": _pct(helpful, fb)}
        out["note"] = f"Based on {fb} customer feedback answer(s) only; customers who gave no feedback are not counted either way."
    return out


def _limits() -> list[str]:
    return ["All counts come from the ticket report and the assistant's recorded feedback; nothing is estimated.",
            "Labels: OBSERVATION is measured; HYPOTHESIS is a possible reason that is not confirmed.",
            "Customer text is not analysed by a language model here, so themes come from ticket categories only.",
            "No SLA is defined, so nothing is reported as a breach."]
