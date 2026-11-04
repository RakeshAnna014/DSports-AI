"""Output grounding: before an answer leaves the service, verify that every authoritative commerce claim in it is backed by
THIS turn's tool results. If anything is not, the model's text is dropped and a deterministic answer is used instead.

What is checked (all programmatic, no model involved):
  * money amounts (rupee symbol / Rs / INR)  must equal a number returned by a tool this turn
  * order / shipment / tracking / payment references must appear in the tool results
  * product names (known catalogue names and bold product-like spans) must belong to products returned this turn
  * status claims (delivered, refund completed, out of stock, approved, eligible ...) must match a status in the results
  * return-eligibility claims need an eligibility result with the same verdict
What this cannot prove: free-form opinion ("good for beginners") - that is allowed, and is shown to the customer as AI
explanation next to the verified data.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

_MONEY = re.compile(r"(?:₹|\bRs\.?|\bINR)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)", re.IGNORECASE)
_REFS = re.compile(r"\b(ORD-\d{8}-\d{6}|TRK-[A-Z0-9]{6,}|SH-\d{5,}|PAY-[0-9A-Z-]{10,})\b")
_BOLD = re.compile(r"\*\*([^*\n]{3,80})\*\*")
_BRANDS = ("mrf", "ss ", "sg ", "kookaburra", "nike", "adidas", "puma", "gray nicolls", "dsc", "spartan")

# (claim regex, what must be in the facts, human label)
_STATUS_RULES: list[tuple[re.Pattern[str], tuple[str, ...], str]] = [(re.compile(p, re.I), req, label) for p, req, label in [
    (r"\b(has been|was|were|is|been|got)\s+delivered\b|\bdelivered (on|at|to you)\b", ("delivered",), "delivered claim"),
    (r"\bout for delivery\b", ("out_for_delivery",), "out for delivery claim"),
    (r"\b(is|has been|was)\s+(currently\s+)?(in transit|on its way)\b", ("in_transit", "picked_up", "out_for_delivery"), "in transit claim"),
    (r"\brefund\b[^.]{0,40}\b(has been|was|is)\s+(completed|processed|issued|credited|paid)\b", ("completed", "refunded"), "refund completed claim"),
    (r"\b(has been|was|is)\s+approved\b", ("approved",), "approved claim"),
    (r"\b(has been|was|is)\s+rejected\b", ("rejected",), "rejected claim"),
    (r"\bout of stock\b|\bsold out\b", ("out_of_stock",), "out of stock claim"),
    (r"\b(is|are)\s+cancelled\b|\bhas been cancelled\b", ("cancelled",), "cancelled claim"),
]]
# Clauses describing a process or a condition ("once it is approved", "until the refund is completed") are policy wording,
# not claims about this customer's data; they are removed before the status rules are applied.
_CONDITIONAL = re.compile(r"\b(until|once|when|whenever|after|before|if|unless|while|whether|will be|to be|needs? to be|must be|should be|"
                          r"can be|may be|gets?|being)\b[^.!?\n]{0,60}", re.I)
_PERSONAL = re.compile(r"\b(your|you|you've|yours|my|mine)\b|\b(the|this|that|an?) (order|shipment|package|parcel|delivery)\b|\bORD-\d", re.I)
_ELIGIBLE = re.compile(r"\b(is|are|you can|can be|qualif\w+ for)\b[^.]{0,30}\b(eligible|returned|return it|return them)\b", re.I)
_NOT_ELIGIBLE = re.compile(r"\b(not eligible|ineligible|cannot be returned|can't be returned|can not be returned|unable to return|no longer eligible)\b", re.I)


@dataclass
class Facts:
    """`numbers`/`strings` come from LIVE data tools only. Retrieved document text is deliberately kept out of them: a policy
    that merely contains the word "COMPLETED" must not make "your refund was completed" pass. Documents count only for
    `all_strings`, which is used to recognise product names a care sheet or guide legitimately talks about."""
    numbers: set[float] = field(default_factory=set)
    strings: set[str] = field(default_factory=set)        # lower-cased
    all_strings: set[str] = field(default_factory=set)
    product_names: set[str] = field(default_factory=set)  # lower-cased
    eligibility: list[bool] = field(default_factory=list)
    statuses: set[str] = field(default_factory=set)   # exact status tokens (DELIVERED, COMPLETED ...) + populated event timestamps, lower-cased
    account_data: bool = False        # the turn contained the customer's own order/payment/refund/return data
    product_data: bool = False        # the turn contained product facts


@dataclass
class Violation:
    kind: str
    text: str


@dataclass
class GroundingReport:
    ok: bool
    violations: list[Violation] = field(default_factory=list)
    checked: int = 0


DOCUMENT_TOOLS = {"get_product_knowledge"}
# Tools whose results are about THIS customer's account. Status claims ("delivered", "refund completed" ...) are only meaningful, and
# only checked, when one of them ran: an explanation of what a status means in general is policy text, not a claim.
ACCOUNT_TOOLS = {"get_orders", "get_order", "track_order", "get_payment_status", "get_refund_status", "check_return_eligibility", "get_cart",
                 "create_return", "reorder_product"}
PRODUCT_TOOLS = {"search_products", "get_product"}
# Diagnostic metadata (e.g. the candidates retrieval considered but the customer was not shown) is never evidence for a claim.
_NOT_EVIDENCE = {"retrieval"}
_STATUS_TOKEN = re.compile(r"^[A-Z][A-Z0-9_]{2,}$")   # DELIVERED, OUT_FOR_DELIVERY ...; a sentence that merely contains "delivered" is not a status
_TIMESTAMP_KEY = re.compile(r"^(delivered|shipped|completed|paid|cancelled|refunded)(?:_at|At|_on)$")


def collect_facts(results: list[Any]) -> Facts:
    """Flatten every tool result of this turn into the sets the checks compare against."""
    facts = Facts()

    def walk(node: Any, live: bool, key: str | None = None) -> None:
        if isinstance(node, bool):
            return
        if isinstance(node, (int, float)):
            if live:
                facts.numbers.add(round(float(node), 2))
        elif isinstance(node, str):
            low = node.lower()
            facts.all_strings.add(low)
            if live:
                facts.strings.add(low)
                if _STATUS_TOKEN.match(node):
                    facts.statuses.add(low)
                if key in ("name", "product_name", "productName"):
                    facts.product_names.add(low)
        elif isinstance(node, dict):
            if live and isinstance(node.get("eligible"), bool):
                facts.eligibility.append(node["eligible"])
            for k, v in node.items():
                if k in _NOT_EVIDENCE:
                    continue
                if live and v and isinstance(v, str):
                    event = _TIMESTAMP_KEY.match(k)
                    if event:   # a populated delivered_at / shipped_at / completed_at / paid_at is itself evidence of that event
                        facts.statuses.add(event.group(1).lower())
                walk(v, live, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, live, key)

    for r in results:
        tool = getattr(r, "tool", None)
        data = r.data if hasattr(r, "data") else r
        facts.account_data = facts.account_data or (tool in ACCOUNT_TOOLS and bool(data))
        facts.product_data = facts.product_data or (tool in PRODUCT_TOOLS and bool(data))
        walk(data, tool not in DOCUMENT_TOOLS)
    return facts


def _has_number(facts: Facts, value: float) -> bool:
    return any(abs(value - n) < 0.005 for n in facts.numbers)


def _in_strings(facts: Facts, needle: str) -> bool:
    return any(needle in s for s in facts.strings)


def check_grounding(text: str, facts: Facts, known_products: set[str] | None = None) -> GroundingReport:
    violations: list[Violation] = []
    checked = 0
    low = text.lower()

    for m in _MONEY.finditer(text):
        checked += 1
        value = float(m.group(1).replace(",", ""))
        if not _has_number(facts, value):
            violations.append(Violation("amount", m.group(0)))

    for m in _REFS.finditer(text):
        checked += 1
        if not _in_strings(facts, m.group(1).lower()):
            violations.append(Violation("reference", m.group(1)))

    for name in (known_products or set()):
        if len(name) >= 6 and re.search(r"(?<![\w])" + re.escape(name) + r"(?![\w])", low):
            checked += 1
            if not (any(name in n or n in name for n in facts.product_names) or any(name in t for t in facts.all_strings)):
                violations.append(Violation("product", name))
    for m in _BOLD.finditer(text):
        span = m.group(1).strip().lower()
        if any(b in f"{span} " for b in _BRANDS):
            checked += 1
            if not (any(span in n or n in span for n in facts.product_names) or any(span in t for t in facts.all_strings)):
                violations.append(Violation("product", m.group(1)))

    claim_text = _CONDITIONAL.sub(" ", text)
    status_scope = claim_text
    if not facts.statuses:
        # The customer has no status records at all (no refund, no shipment ...): only sentences addressed to them ("your refund has
        # been approved") can be claims about their data. General policy wording ("a refund is approved by staff") is not.
        status_scope = " ".join(sent for sent in re.split(r"(?<=[.!?])\s+|\n", claim_text) if _PERSONAL.search(sent))
    for pattern, required, label in _STATUS_RULES:
        applies = facts.product_data if "stock" in label else facts.account_data
        if applies and pattern.search(status_scope if "stock" not in label else claim_text):
            checked += 1
            if not any(r in facts.statuses for r in required):
                violations.append(Violation("status", label))

    # A verdict about THIS customer's product must match the one DSports returned. (Generic policy wording such as "returns are
    # accepted within 7 days" is allowed without a check, so this only applies when an eligibility check ran this turn.)
    if facts.eligibility:
        claims_not = bool(_NOT_ELIGIBLE.search(claim_text))
        claims_yes = bool(_ELIGIBLE.search(claim_text)) and not claims_not
        if claims_yes or claims_not:
            checked += 1
            if claims_yes and not any(facts.eligibility):
                violations.append(Violation("eligibility", "claims eligible but the check said not eligible"))
            elif claims_not and all(facts.eligibility):
                violations.append(Violation("eligibility", "claims not eligible but the check said eligible"))

    return GroundingReport(not violations, violations, checked)
