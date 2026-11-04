"""Input guard: stops requests that try to subvert the assistant BEFORE any model or tool is involved.

The refusals are fixed, deterministic texts. Even if a request slipped past these patterns it could not do harm: tools
take their identity from the verified token, never from arguments, and the backend enforces ownership.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from app.guard.untrusted import find_injection
from app.obs import metrics

MAX_MESSAGE_CHARS = 1000

_EXTRACTION = re.compile(
    r"\b(system prompt|your (initial |hidden |secret |original )?(instructions|prompt|rules)|developer (message|prompt)|"
    r"how (were|are) you (programmed|configured|instructed)|repeat (everything|the text) above|"
    r"(print|show|reveal|display|dump|output|leak|tell me)\b[^.?!]{0,15}\b(your|the assistant'?s|the system'?s|its)\b[^.?!]{0,10}\b(configuration|config|settings|setup|internal (rules|state|data)))\b|"
    r"\b(disregard|ignore|forget)\b[^.?!]{0,25}\b(everything|all|anything)\b[^.?!]{0,15}\b(above|before|previously|prior|earlier)\b", re.IGNORECASE)
_CREDENTIALS = re.compile(
    r"\b(api[ _-]?key|secret key|private key|service[ -]account|jwt( secret)?|signing (key|secret)|bearer token|access token|"
    r"refresh token|environment variables?|\.env|database (password|url)|connection string|your credentials)\b", re.IGNORECASE)
_CROSS_CUSTOMER = re.compile(
    r"(\b(another|other|different|someone else'?s?|somebody else'?s?|a stranger'?s?|all|every|any)\s+"
    r"(customers?|users?|persons?|people|accounts?)'?s?\b[^.?!]{0,40}\b(orders?|addresses|address|data|details|payments?|refunds?|returns?|info|information|emails?|phone)\b)|"
    r"(\b(orders?|payments?|refunds?|returns?|addresses)\b[^.?!]{0,25}\b(of|for|from|belonging to)\s+(customer|user|account)\s+\S+)|"
    r"(\b(orders?|payments?|refunds?|returns?|addresses|details|data|info|information)\b[^.?!]{0,25}\b(of|for|from|belonging to)\s+"
    r"(another|other|different|someone else|somebody else|a stranger|all|every|any)\s+(customers?|users?|persons?|people|accounts?)\b)|"
    r"(\b(customer|user)[ _-]?id\b)|(\blist (all|every) (orders?|customers?|users?)\b)", re.IGNORECASE)
_ESCALATION = re.compile(r"\b(i am|i'm|act as|you are|as) (an? |the |my )?(admin|administrator|staff|employee|developer|root|superuser|manager|supervisor|"
                         r"(?:support|customer (?:care|service|support)|dsports)(?: team)? (?:executive|agent|staff|representative|manager|member|employee))\b|"
                         r"\b(admin|root) (access|mode|privileges?)\b|\bsudo\b", re.IGNORECASE)

# ---- Phase 3 (support): somebody else's cases, staff-only actions, internal staff information
_NAMED_PERSON = re.compile(r"\b(?!My\b|Your\b|The\b|Customer\b|Support\b|DSports\b|Our\b)[A-Z][a-z]{2,}'s\s+(?:[Cc]ases?|[Cc]omplaints?|[Tt]ickets?|[Oo]rders?|[Pp]ayments?|[Rr]efunds?|[Rr]eturns?|[Aa]ddress(?:es)?)\b")
_OTHERS_CASES = re.compile(r"\b(?:another|other|different|someone else'?s?|somebody else'?s?|all|every|any)\s+(?:customers?|users?|people|persons?)?'?s?\s*(?:cases?|complaints?|tickets?)\b|"
                           r"\b(?:cases?|complaints?|tickets?)\b[^.?!]{0,25}\b(?:of|from|belonging to|raised by|opened by)\s+(?:another|other|different|someone|somebody|a stranger|customer|user|all|every|any)\b|"
                           r"\b(?:list|show|display|dump|export|give me|see)\s+(?:me\s+)?(?:all|every|each)\b[^.?!]{0,18}\b(?:cases?|complaints?|tickets?)\b|\bguess(?:ed)? (?:a )?(?:case|ticket)\b", re.IGNORECASE)
_STAFF_ACTION = re.compile(
    r"^(?:[^.?!]{0,60}[,;:]\s*)?\W*(?:please\W*)?(?:can you\W*|could you\W*|would you\W*|just\W*|go ahead and\W*)*"
    r"(?:(?:close|resolve|delete|remove|withdraw|archive)\b[^.?!]{0,15}\b(?:cases?|complaints?|tickets?)\b"
    r"|mark\b[^.?!]{0,25}\b(?:cases?|complaints?|tickets?|it)\b[^.?!]{0,15}\b(?:as )?(?:resolved|closed|solved|done|fixed)\b"
    r"|(?:refund|reimburse|compensate|pay back|credit)\s+me\b|pay me back\b|(?:give|send|return|get)\s+(?:me\s+)?(?:my|the)\s+money\s+back\b|(?:send|return|transfer)\s+(?:me\s+)?(?:my|the)\s+money\b"
    r"|(?:issue|process|send|give|make|approve|authori[sz]e|grant|release)\b[^.?!]{0,12}\b(?:me\b[^.?!]{0,6})?(?:a |my |the )?(?:refund|reimbursement|compensation|chargeback)\b"
    r"|(?:approve|accept|authori[sz]e|grant|confirm)\b[^.?!]{0,12}\b(?:my|the|this)\b[^.?!]{0,12}\b(?:return|refund|exchange|replacement|request|claim)\b"
    r"|(?:cancel|void|stop)\b[^.?!]{0,12}\b(?:my|the|this)\b[^.?!]{0,12}\b(?:order|payment|shipment|delivery)\b"
    r"|(?:reverse|revert|undo|charge ?back)\b[^.?!]{0,15}\b(?:payment|charge|transaction|debit)\b"
    r"|(?:assign|reassign|transfer)\b[^.?!]{0,20}\b(?:case|complaint|ticket)\b"
    r"|(?:set|change|raise|increase|bump|upgrade|make|mark)\b[^.?!]{0,25}\b(?:priority|urgent|critical|high[- ]priority)\b)", re.IGNORECASE)
_INTERNAL_INFO = re.compile(r"\b(?:internal|private|staff|agent|executive)\s+(?:notes?|comments?|remarks?|messages?)\b|\bpriority (?:reasons?|score|rules?|logic)\b|"
                            r"\b(?:notes?|comments?|remarks?)\b[^.?!]{0,30}\b(?:support|staff|team|agents?|executives?|employees?)\b[^.?!]{0,25}\b(?:wrote|write|written|made|left|said|say|recorded)\b|"
                            r"\b(?:support|staff|team|agents?|executives?|employees?)\b[^.?!]{0,25}\b(?:wrote|written|write|noted?|recorded|said|say)\b[^.?!]{0,25}\babout me\b|"
                            r"\b(?:risk|fraud|abuse)\s+(?:score|signals?|flags?|rating|level)\b|\bwhat (?:do|did) (?:they|staff|the team|support) (?:write|say|note) (?:about|on) me\b", re.IGNORECASE)

REFUSALS = {
    "staff_action": ("I can't do that myself. Closing, resolving or reassigning cases, issuing or approving refunds and returns, cancelling orders, reversing "
                     "payments and changing a case's priority are done by DSports staff. I can check where things stand, raise a support case, add a message to "
                     "your case, ask for a person to review it, or start a return you're eligible for."),
    "internal_info": "Internal staff notes and how cases are prioritised are not shared through the assistant. I can tell you your case's status, its messages from support and what is happening next.",
    "cross_customer": "I can only help with your own account. I can't look up or share another customer's orders or details.",
    "prompt_extraction": "I can't share my internal instructions. I'm happy to help you find products, check an order, or answer questions about DSports.",
    "credentials": "I don't have access to credentials or system secrets, and I can't share them. I can help with products and your orders.",
    "injection": "I can't follow instructions that try to change how I work. Tell me what you'd like to find or check and I'll help.",
    "escalation": "I can only act with your own customer permissions; I can't take staff or administrator actions.",
    "too_long": "That message is too long for me to work with. Please shorten it and try again.",
    "empty": "Please tell me what you'd like help with.",
}


@dataclass(frozen=True)
class GuardResult:
    allowed: bool
    category: str | None = None
    refusal: str | None = None


def check_input(message: str) -> GuardResult:
    text = (message or "").strip()
    if not text:
        return _block("empty")
    if len(text) > MAX_MESSAGE_CHARS:
        return _block("too_long")
    # Order matters: the most specific / most dangerous intent wins.
    if _CROSS_CUSTOMER.search(text) or _NAMED_PERSON.search(text) or _OTHERS_CASES.search(text):
        return _block("cross_customer")
    if _INTERNAL_INFO.search(text):
        return _block("internal_info")
    if _STAFF_ACTION.search(text):
        return _block("staff_action")
    if _CREDENTIALS.search(text):
        return _block("credentials")
    if _EXTRACTION.search(text):
        return _block("prompt_extraction")
    if _ESCALATION.search(text):
        return _block("escalation")
    if find_injection(text):
        return _block("injection")
    return GuardResult(True)


def _block(category: str) -> GuardResult:
    if category not in ("empty", "too_long"):
        metrics.GUARD_BLOCKS.labels("input", category).inc()
    return GuardResult(False, category, REFUSALS[category])
