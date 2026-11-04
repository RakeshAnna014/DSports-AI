"""The vocabulary of a support investigation.

An investigation separates three kinds of statement and never mixes them:
  * VERIFIED FACTS      read from the customer's own records through DSports' public APIs, or from a cited policy passage, or the result of an
                        action the backend confirmed. Each carries where it came from.
  * INFERENCES          possible explanations. Always worded as possibilities and always say what they rest on.
  * MISSING INFORMATION things that could not be verified, and why that matters.
The decision engine (resolve.py) is a pure function of an Investigation; the model never decides an outcome.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class Complaint(StrEnum):
    DELAYED_DELIVERY = "DELAYED_DELIVERY"        # "has not arrived": becomes MISSING_DELIVERY when the shipment says delivered
    MISSING_DELIVERY = "MISSING_DELIVERY"        # "says delivered but I did not receive it"
    PARTIAL_DELIVERY = "PARTIAL_DELIVERY"
    WRONG_PRODUCT = "WRONG_PRODUCT"
    DAMAGED_PRODUCT = "DAMAGED_PRODUCT"
    WRONG_SIZE = "WRONG_SIZE"                    # the customer ordered the wrong size: an exchange request, not a DSports error
    EXCHANGE_REQUEST = "EXCHANGE_REQUEST"
    PAYMENT_FAILED = "PAYMENT_FAILED"            # "deducted but the order failed"
    DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT"
    PAYMENT_PENDING = "PAYMENT_PENDING"
    REFUND_DELAYED = "REFUND_DELAYED"
    REFUND_PARTIAL = "REFUND_PARTIAL"
    RETURN_REJECTED = "RETURN_REJECTED"
    RETURN_BLOCKED = "RETURN_BLOCKED"            # "I cannot return my product"
    RETURN_PICKUP = "RETURN_PICKUP"
    PRODUCT_DEFECT = "PRODUCT_DEFECT"
    PRODUCT_MISMATCH = "PRODUCT_MISMATCH"        # does not match the advertised specification
    PRODUCT_MANUAL = "PRODUCT_MANUAL"
    TECHNICAL_ISSUE = "TECHNICAL_ISSUE"          # "the website will not let me log in": no order involved, a person has to look
    GENERAL = "GENERAL"                          # "I want to raise a complaint", "can I speak to someone"


# The case category the Java backend knows. The mapping is explicit so a new complaint type cannot silently pick a wrong category.
CASE_CATEGORY: dict[Complaint, str] = {
    Complaint.DELAYED_DELIVERY: "DELAYED_DELIVERY", Complaint.MISSING_DELIVERY: "MISSING_DELIVERY", Complaint.PARTIAL_DELIVERY: "PARTIAL_DELIVERY",
    Complaint.WRONG_PRODUCT: "WRONG_PRODUCT", Complaint.DAMAGED_PRODUCT: "DAMAGED_PRODUCT", Complaint.WRONG_SIZE: "EXCHANGE_REQUEST",
    Complaint.EXCHANGE_REQUEST: "EXCHANGE_REQUEST", Complaint.PAYMENT_FAILED: "PAYMENT_ISSUE", Complaint.DUPLICATE_PAYMENT: "DUPLICATE_PAYMENT",
    Complaint.PAYMENT_PENDING: "PAYMENT_ISSUE", Complaint.REFUND_DELAYED: "REFUND_ISSUE", Complaint.REFUND_PARTIAL: "REFUND_ISSUE",
    Complaint.RETURN_REJECTED: "RETURN_ISSUE", Complaint.RETURN_BLOCKED: "RETURN_ISSUE", Complaint.RETURN_PICKUP: "RETURN_ISSUE",
    Complaint.PRODUCT_DEFECT: "PRODUCT_COMPLAINT", Complaint.PRODUCT_MISMATCH: "PRODUCT_COMPLAINT", Complaint.PRODUCT_MANUAL: "PRODUCT_COMPLAINT",
    Complaint.TECHNICAL_ISSUE: "TECHNICAL_ISSUE", Complaint.GENERAL: "GENERAL",
}

DELIVERY = {Complaint.DELAYED_DELIVERY, Complaint.MISSING_DELIVERY, Complaint.PARTIAL_DELIVERY}
PAYMENT = {Complaint.PAYMENT_FAILED, Complaint.DUPLICATE_PAYMENT, Complaint.PAYMENT_PENDING}
REFUND = {Complaint.REFUND_DELAYED, Complaint.REFUND_PARTIAL}
RETURNS = {Complaint.RETURN_REJECTED, Complaint.RETURN_BLOCKED, Complaint.RETURN_PICKUP}
PRODUCT_LINE = {Complaint.WRONG_PRODUCT, Complaint.DAMAGED_PRODUCT, Complaint.WRONG_SIZE, Complaint.EXCHANGE_REQUEST, Complaint.PRODUCT_DEFECT,
                Complaint.PRODUCT_MISMATCH, Complaint.PRODUCT_MANUAL}

# Complaints that are about one product line of an order (the customer must say which product).
NEEDS_PRODUCT = {Complaint.WRONG_PRODUCT, Complaint.DAMAGED_PRODUCT, Complaint.WRONG_SIZE, Complaint.EXCHANGE_REQUEST, Complaint.PRODUCT_DEFECT,
                 Complaint.PRODUCT_MISMATCH, Complaint.PRODUCT_MANUAL, Complaint.RETURN_BLOCKED}
# Complaints that cannot be investigated without knowing the order.
NEEDS_ORDER = (DELIVERY | NEEDS_PRODUCT | REFUND | RETURNS | {Complaint.PAYMENT_PENDING, Complaint.DUPLICATE_PAYMENT}) - {Complaint.PRODUCT_MANUAL}


@dataclass(frozen=True)
class Fact:
    kind: str                 # ORDER | SHIPMENT | PAYMENT | RETURN | REFUND | PRODUCT | POLICY
    statement: str            # one factual sentence
    ref: str | None = None    # order number, tracking number, payment reference ...
    source: str = "DSPORTS_API"   # DSPORTS_API | POLICY | ACTION_RESULT

    def to_json(self) -> dict[str, Any]:
        return {"kind": self.kind, "statement": self.statement, "ref": self.ref, "source": self.source}


@dataclass(frozen=True)
class Inference:
    statement: str            # worded as a possibility
    basis: str                # which facts it rests on

    def to_json(self) -> dict[str, Any]:
        return {"statement": self.statement, "basis": self.basis}


@dataclass(frozen=True)
class Missing:
    item: str
    why: str

    def to_json(self) -> dict[str, Any]:
        return {"item": self.item, "why": self.why}


@dataclass
class Investigation:
    """Everything one investigation established. `signals` are machine-readable flags the decision engine reads (never the model)."""
    complaint: Complaint
    effective: Complaint                                   # the type after the evidence was read (delayed -> missing when the shipment says delivered)
    order: dict[str, Any] | None = None                    # {order_id, order_number, status, total, items[]}
    product: dict[str, Any] | None = None                  # {product_id, name}
    facts: list[Fact] = field(default_factory=list)
    inferences: list[Inference] = field(default_factory=list)
    missing: list[Missing] = field(default_factory=list)
    policy: list[dict[str, Any]] = field(default_factory=list)      # cited passages: {source, title, chunk_id, snippet}
    signals: dict[str, Any] = field(default_factory=dict)
    unverified: list[str] = field(default_factory=list)    # what could not be checked because a lookup failed
    tool_failures: list[str] = field(default_factory=list)
    safety_related: bool = False

    def add(self, fact: Fact) -> None:
        if fact not in self.facts:
            self.facts.append(fact)

    def to_json(self) -> dict[str, Any]:
        return {"complaint": self.complaint.value, "effective": self.effective.value, "order": self.order, "product": self.product,
                "facts": [f.to_json() for f in self.facts], "inferences": [i.to_json() for i in self.inferences],
                "missing": [m.to_json() for m in self.missing], "policy": self.policy, "signals": self.signals,
                "unverified": self.unverified, "safety_related": self.safety_related}


class Outcome(StrEnum):
    ANSWER = "ANSWER"                  # the verified data answers it; nothing more is needed (a case is still offered)
    ASK_INFO = "ASK_INFO"              # essential information is missing: ask for it
    SAFE_NEXT_STEP = "SAFE_NEXT_STEP"  # recommend a step the customer can take (for example a return the backend allows)
    CREATE_CASE = "CREATE_CASE"        # a person has to look at it: propose opening a case
    ESCALATE = "ESCALATE"              # as CREATE_CASE, and a human review is explicitly requested on the case
    PERFORM_ACTION = "PERFORM_ACTION"  # a supported backend action, after confirmation (for example create_return)
    UNVERIFIABLE = "UNVERIFIABLE"      # the backend could not be reached: say so, create nothing


@dataclass
class Decision:
    outcome: Outcome
    category: str                                      # backend case category
    recommendation: str                                # one plain sentence about what happens next
    escalation_reason: str | None = None
    case_subject: str | None = None
    ask: str | None = None
    actions: list[tuple[str, str, dict[str, Any]]] = field(default_factory=list)   # (label, intent value, slots) offered as server-owned choices
    reasons: list[str] = field(default_factory=list)   # why the engine decided this (shown to staff, auditable)

    def to_json(self) -> dict[str, Any]:
        return {"outcome": self.outcome.value, "category": self.category, "recommendation": self.recommendation,
                "escalation_recommended": self.escalation_reason is not None, "escalation_reason": self.escalation_reason,
                "ask": self.ask, "reasons": self.reasons}
