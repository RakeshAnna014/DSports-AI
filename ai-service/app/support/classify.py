"""Deterministic complaint classification (Tier 0 for support). Zero model tokens.

Rules are intentionally narrow and ordered from specific to general: a wrong rule is worse than a model call, so anything these rules do
not recognise goes to the Tier-1 classifier. Classification only names the PROBLEM TYPE; whether it is real, and what happens next, is
decided later from the customer's own records.
"""
from __future__ import annotations

import re

from app.support.model import Complaint

_I = re.IGNORECASE

# ---- delivery
_DELIVERED_NOT_RECEIVED = re.compile(
    r"\b(says?|shows?|showing|marked|status|tracking|app|website|notification)\b[^.?!]{0,60}\bdelivered\b[^.?!]{0,80}\b(not|never|didn'?t|did not|haven'?t|have not|hasn'?t|no)\b"
    r"|\bdelivered\b[^.?!]{0,30}\b(but|yet|although|though)\b[^.?!]{0,50}\b(not|never|didn'?t|did not|haven'?t|have not|no)\b[^.?!]{0,25}\b(receiv|got|arriv|see|find|found|here|there|delivered)"
    r"|\b(never|not) (actually )?(received|got|got it|arrived)\b[^.?!]{0,40}\bdelivered\b"
    r"|\b(package|parcel|order|shipment)\b[^.?!]{0,20}\b(is )?(lost|missing)\b", _I)
_PARTIAL = re.compile(r"\b(received|got|came with|arrived with|delivered)\b[^.?!]{0,20}\b(only|just)\b[^.?!]{0,25}\b(one|1|two|2|three|3|some|part|half)\b"
                      r"|\b(only|just)\s+(one|1|two|2|three|3)\s+(of|out of)\b|\b(one|an?|some|the other) (of the )?items?\b[^.?!]{0,30}\b(missing|not (in|there|delivered|received|arrived))\b"
                      r"|\bmissing\b[^.?!]{0,15}\b(an? |one |the )?items?\b|\b(second|other|remaining) (item|product|bat|ball|pair)\b[^.?!]{0,25}\b(missing|didn'?t|did not|not)\b", _I)
_NOT_ARRIVED = re.compile(r"\b(has ?n[o']t|hasn'?t|has not|have ?n[o']t|haven'?t|have not|did ?n[o']t|didn'?t|did not|not|never|yet to|still waiting)\b[^.?!]{0,25}\b(arriv\w*|come|came|reach\w*|been delivered|delivered|show(ed)? up|got(?:ten)?|receiv\w*)\b"
                          r"|\b(?:way |long |well )?(?:past|beyond|after)\b[^.?!]{0,12}\b(?:the |your )?(?:promised|expected|estimated|delivery|due)\b[^.?!]{0,10}\b(?:date|time|day|deadline|estimate)?\b"
                          r"|\b(delayed|late|delay|overdue|stuck|taking (too|so) long|taking forever|not moving|hasn'?t moved|not moved|no update|hasn'?t been updated)\b", _I)
_ORDER_WORDS = re.compile(r"\b(order|package|parcel|shipment|delivery|item|product|shoes?|bat|ball|gloves?|jersey|kit)\b", _I)

# ---- product problems
_WRONG_SIZE = re.compile(r"\b(i )?(ordered|bought|picked|chose|selected)\b[^.?!]{0,15}\bthe wrong (size|colou?r|one)\b"
                         r"|\b(size|it|they)\b[^.?!]{0,15}\b(doesn'?t|does not|don'?t|do not|isn'?t|is not|wasn'?t|won'?t) (fit|the right size)\b"
                         r"|\btoo (small|big|large|tight|loose|short|long)\b|\bneed (a )?(bigger|smaller|different|another) size\b", _I)
_WRONG_PRODUCT = re.compile(r"\bwrong (product|item|one|thing|bat|ball|shoes?|gloves?|jersey|colou?r|model|size)\b[^.?!]{0,25}\b(deliver|sent|send|ship|receiv|came|arriv|got)"
                            r"|\b(deliver\w*|sent|shipped|received|got|came|arrived)\b[^.?!]{0,25}\b(the )?wrong (product|item|one|thing|bat|ball|shoes?|gloves?|jersey|colou?r|model|size)\b"
                            r"|\b(not|isn'?t|wasn'?t) what i (ordered|bought|paid for)\b|\bdifferent (product|item|one|model|colou?r)\b[^.?!]{0,25}\b(deliver|sent|received|came|arrived)", _I)
_DAMAGED = re.compile(r"\b(damaged|damage|broken|crack(?:ed|s)?|splinter(?:ed|s)?|torn|ripped|smashed|crushed|dented|split|chipped|scratched|tampered|leaking|punctured|ruined)\b"
                      r"|\barrived (in )?(bad|poor) (condition|shape)\b|\bdefective packaging\b", _I)
_DEFECT = re.compile(r"\b(defective|faulty|not working|stopped working|doesn'?t work|does not work|came apart|coming apart|falling apart|peeling|poor quality|"
                     r"malfunction\w*|stitching (is )?(coming|come|came) (off|apart|loose)|sole (is )?(coming|come|came|peeling))\b", _I)
_MISMATCH = re.compile(r"\b(specs?|specifications?|description|photos?|pictures?|images?|listing|details|advertis\w+|claims?)\b[^.?!]{0,50}\b(do not|don'?t|does not|doesn'?t|did not|didn'?t|not) (match|same|as)\b"
                       r"|\bnot as (described|advertised|shown|pictured|listed)\b|\b(misleading|false|wrong) (description|listing|advertis\w+|specifications?|specs?)\b", _I)
_MANUAL = re.compile(r"\b(manual|instructions?|user guide|guide|leaflet)\b[^.?!]{0,50}\b(does not|doesn'?t|do not|don'?t|missing|not (include|explain|provide|cover|clear)|unclear)\b"
                     r"|\bno (manual|instructions?)\b", _I)
_EXCHANGE = re.compile(r"\b(exchange|swap|replace|replacement)\b[^.?!]{0,30}\b(this|it|my|the|item|product|order|for)\b|\b(want|like|need|wish) (to |a )?(exchange|replacement|swap)\b", _I)

# ---- payments
_DUPLICATE = re.compile(r"\b(charged|debited|deducted|billed|paid|taken|cut)\b[^.?!]{0,30}\b(twice|two times|double|2 times|2x|again|two payments?)\b"
                        r"|\b(double|duplicate|twice|two)\b[^.?!]{0,20}\b(charge\w*|payment|deduction|debit\w*)\b|\bpaid (for it )?twice\b", _I)
_PAYMENT_FAILED = re.compile(r"\b(money|amount|payment|charge|funds)\b[^.?!]{0,50}\b(deducted|debited|charged|cut|taken|withdrawn)\b[^.?!]{0,60}\b(order|booking|purchase)\b[^.?!]{0,30}\b(fail\w*|not (placed|confirmed|created|showing|received|successful)|cancel\w*|no order|didn'?t (go|work|show))"
                             r"|\b(order|booking|purchase)\b[^.?!]{0,25}\b(fail\w*|not (placed|confirmed|created|successful)|cancel\w*)\b[^.?!]{0,60}\b(deducted|debited|charged|money|amount|paid)\b"
                             r"|\b(payment|money|amount)\b[^.?!]{0,30}\b(deducted|debited|charged|cut|taken)\b[^.?!]{0,20}\b(but|and)\b[^.?!]{0,30}\b(no order|order (not|failed|fail)|failed|didn'?t go through)", _I)
_PAYMENT_PENDING = re.compile(r"\bpayment\b[^.?!]{0,25}\b(is )?(still )?(pending|processing|stuck|in progress|not (completed|confirmed|updated))\b"
                              r"|\b(confirmation|confirmed)\b[^.?!]{0,60}\bpayment\b[^.?!]{0,30}\b(status|wrong|pending|failed|looks|incorrect|not)\b|\bpayment status\b[^.?!]{0,20}\b(wrong|incorrect|odd|strange|not right|looks wrong)\b", _I)

# ---- refunds and returns
_REFUND_PARTIAL = re.compile(r"\b(only|just)\b[^.?!]{0,25}\b(part|partial|half|some|portion)\b[^.?!]{0,20}\brefund\w*\b|\brefund\w*\b[^.?!]{0,25}\b(is |was |came |only )?(partial|less|short|lower|incomplete|not (the )?full|not complete)\b"
                             r"|\b(received|got)\b[^.?!]{0,20}\b(only|just)\b[^.?!]{0,30}\brefund\b", _I)
_REFUND_COMPLAINT = re.compile(r"\b(return|returned)\b[^.?!]{0,50}\b(approved|accepted|completed|picked)\b[^.?!]{0,40}\b(but|yet|and|still)\b[^.?!]{0,25}\b(not|haven't|hasn't|have not|has not|didn't|did not|never)\b[^.?!]{0,20}\b(receiv\w*|got|get|see|seen)\b[^.?!]{0,20}\b(refund|money|amount|payment)\b"
                               r"|\b(return|returned|pickup|picked up|collected)\b[^.?!]{0,50}\b(approved|accepted|received|completed|done|picked)\b[^.?!]{0,70}\b(refund|money|amount|paid)\b[^.?!]{0,40}\b(not|haven'?t|hasn'?t|have not|has not|didn'?t|did not|never|yet|still|pending|delay\w*)\b"
                               r"|\b(refund|money)\b[^.?!]{0,40}\b(not|haven'?t|hasn'?t|have not|has not|didn'?t|did not|never|yet)\b[^.?!]{0,30}\b(receiv\w*|got|credit\w*|arriv\w*|show\w*)\b[^.?!]{0,60}\b(return|returned|approved)\b"
                               r"|\b(haven'?t|have not|didn'?t|did not|not)\b[^.?!]{0,20}\b(receiv\w*|got|get)\b[^.?!]{0,10}\b(my )?(refund|money)\b[^.?!]{0,60}\b(approved|return)", _I)
_RETURN_REJECTED = re.compile(r"\b(return|returns|return request|my return)\b[^.?!]{0,40}\b(was |got |been |is )?(rejected|declined|denied|refused|turned down|not approved)\b"
                              r"|\b(rejected|declined|denied|refused)\b[^.?!]{0,25}\b(my |the )?return\b", _I)
_RETURN_BLOCKED = re.compile(r"\b(i )?(can'?t|cannot|can not|unable to|not able to|am not able to|not allowed to|isn'?t letting me|won'?t let me)\b[^.?!]{0,15}\b(return|send back)\b"
                             r"|\b(return|send back)\b[^.?!]{0,20}\b(option|button|link)\b[^.?!]{0,20}\b(missing|not (showing|available|working)|disabled|greyed)\b", _I)
_RETURN_PICKUP = re.compile(r"\b(pick ?up|pickup|collection|collect)\b[^.?!]{0,40}\b(did ?n[o']t|didn'?t|did not|never|hasn'?t|has not|not|no one|nobody|missed|failed)\b[^.?!]{0,20}\b(happen|come|turn up|show|arrive|made|taken|done|was|scheduled)?\b"
                            r"|\b(no ?one|nobody|courier|driver|agent)\b[^.?!]{0,20}\b(came|turned up|showed up|picked|collected)\b[^.?!]{0,10}\b(up|for)?\b[^.?!]{0,30}\b(return|pickup|pick up)?", _I)

# ---- website / app problems (no order involved)
_TECHNICAL = re.compile(r"\b(website|web ?site|the site|app|checkout page|login page|log ?in|sign ?in|password reset|otp|page)\b[^.?!]{0,40}\b(not (working|loading|opening|responding)|crash\w*|broken|error|bug|glitch\w*|freez\w*|frozen|stuck|is down|won'?t (load|open|work)|doesn'?t (load|open|work)|can'?t (load|open)|keeps? (crashing|failing|freezing))\b"
                        r"|\b(can'?t|cannot|unable to|not able to|won'?t let me|isn'?t letting me)\b[^.?!]{0,15}\b(log ?in|sign ?in|register|check ?out|add (it |this |items? )?to (the |my )?cart|apply (a |the |my )?(coupon|code))\b"
                        r"|\b(error|bug|glitch)\b[^.?!]{0,25}\b(on|in|with) (the )?(website|site|app|checkout|page)\b", _I)

# ---- generic
_GENERAL =re.compile(r"\b(raise|file|make|register|lodge|log|open|create|submit|send|write)\b[^.?!]{0,15}\b(a |an |my )?(complaint|case|ticket|grievance|issue|request)\b"
                      r"|\b(i )?(want|need|would like|wish)\b[^.?!]{0,20}\b(to )?(complain|escalate)\b|\bi (have|got|am making) (a )?complaint\b|\bescalate\b[^.?!]{0,30}\b(this|my|it|the)\b", _I)
_WANTS_HUMAN = re.compile(r"\b(speak|talk|chat|connect|transfer)\b[^.?!]{0,20}\b(to|with)\b[^.?!]{0,15}\b(a |an |the )?(human|person|agent|someone|somebody|representative|executive|support|manager|supervisor|customer (care|service|support))\b"
                          r"|\b(human|real person|live agent|customer (care|service|support) (agent|executive|representative|team|number))\b|\bcall me\b|\bsupport (team|executive|agent)\b", _I)

_VAGUE_PROBLEM = re.compile(r"\b(?:something(?:'s| is)? (?:is )?(?:wrong|off|not right)|there(?:'s| is) (?:a )?(?:problem|issue|trouble)|(?:a )?(?:problem|issue|trouble) with (?:my|the|an?) (?:order|delivery|purchase|payment|refund|return|account)|"
                            r"need help with (?:my|the) (?:order|delivery|purchase|payment|refund|return))\b", _I)

# ---- explicit safety vocabulary. The flag only ever comes from words the customer wrote; tone and complaint history are never used.
_SAFETY = re.compile(r"\b(injur\w*|hurt|bleed\w*|burn(t|ed|s)?|fire|smoke|smok\w*|sparks?|electric shock|shock(ed)?|unsafe|not safe|dangerous|hazard\w*|choking|chok\w+|poison\w*|allerg\w+|rash|fracture\w*|concuss\w*|hospital|safety)\b", _I)


_INJURY = re.compile(r"\b(?:cut|gashed|sliced|bruised|sprained|wounded|scratched|splintered|pinched|crushed)\b[^.?!]{0,20}\b(?:me|my|his|her|their|our)\b|"
                     r"\b(?:cut|gashed|bruised|sprained|wounded|hurt|injured)\b[^.?!]{0,12}\b(?:hand|finger|fingers|arm|leg|knee|ankle|face|skin|foot|wrist)\b", _I)


def safety_flag(message: str) -> bool:
    """Only words the customer wrote: explicit safety vocabulary or an explicit injury to a person. Tone and history are never used."""
    return bool(_SAFETY.search(message) or _INJURY.search(message))


def wants_human(message: str) -> bool:
    return bool(_WANTS_HUMAN.search(message))


# A general "what should I do if ..." question is about the POLICY, not a report of something that happened to this customer: no first-person experience words.
_HYPOTHETICAL = re.compile(r"^\W*(?:what (?:should|do|can|happens|will)|how (?:do|can|should|does|will)|can (?:i|you)|should i|is it|are there)\b[^.?!]{0,60}\b(?:if|when|whenever|in case|after)\b", _I)
_EXPERIENCE = re.compile(r"\b(?:my|mine|i've|i have|i received|i got|arrived|came|was|were|has|have been|had)\b", _I)


_STATUS_QUESTION = re.compile(r"^\W*(?:is|are|was|were|has|have|did|does|do|will)\b[^?]*\?\s*$", _I)


def classify(message: str) -> Complaint | None:
    """The complaint type if a rule is confident, else None (=> Tier 1). Order matters: specific before general."""
    found = _classify(message)
    if found in (Complaint.PAYMENT_PENDING, Complaint.DELAYED_DELIVERY) and _STATUS_QUESTION.match(message.strip()):
        return None                         # "Is my payment pending?" / "Has my order arrived?" ask for a status (Phase 1 answers it); they do not report a problem
    return found


def _classify(message: str) -> Complaint | None:
    t = message.strip()
    if not t:
        return None
    if _HYPOTHETICAL.match(t) and not _EXPERIENCE.search(t):
        return None
    # payments first: "charged twice" must not be read as a delivery or product complaint
    if _DUPLICATE.search(t):
        return Complaint.DUPLICATE_PAYMENT
    if _PAYMENT_FAILED.search(t):
        return Complaint.PAYMENT_FAILED
    if _PAYMENT_PENDING.search(t):
        return Complaint.PAYMENT_PENDING
    # refunds and returns
    if _REFUND_PARTIAL.search(t):
        return Complaint.REFUND_PARTIAL
    if _REFUND_COMPLAINT.search(t):
        return Complaint.REFUND_DELAYED
    if _RETURN_REJECTED.search(t):
        return Complaint.RETURN_REJECTED
    if _RETURN_PICKUP.search(t) and re.search(r"\b(return|pick ?up|pickup)\b", t, _I):
        return Complaint.RETURN_PICKUP
    if _RETURN_BLOCKED.search(t):
        return Complaint.RETURN_BLOCKED
    # what was delivered
    if _WRONG_SIZE.search(t) and not re.search(r"\b(wrong|different) (size|one)\b[^.?!]{0,20}\b(deliver|sent|send|ship|receiv|came|arriv)", t, _I):
        return Complaint.WRONG_SIZE
    if _WRONG_PRODUCT.search(t):
        return Complaint.WRONG_PRODUCT
    if _MISMATCH.search(t):
        return Complaint.PRODUCT_MISMATCH
    if _MANUAL.search(t):
        return Complaint.PRODUCT_MANUAL
    if _DAMAGED.search(t) and _ORDER_WORDS.search(t) and not re.search(r"\b(do not|don'?t|doesn'?t) (cover|include)\b", t, _I):
        return Complaint.DAMAGED_PRODUCT
    if _DEFECT.search(t):
        return Complaint.PRODUCT_DEFECT
    if _EXCHANGE.search(t):
        return Complaint.EXCHANGE_REQUEST
    if _TECHNICAL.search(t):
        return Complaint.TECHNICAL_ISSUE
    if _VAGUE_PROBLEM.search(t):
        return Complaint.GENERAL            # "something is wrong with my order": a complaint with no detail yet; the assistant asks what it is
    # delivery
    if _DELIVERED_NOT_RECEIVED.search(t):
        return Complaint.MISSING_DELIVERY
    if _PARTIAL.search(t):
        return Complaint.PARTIAL_DELIVERY
    if _NOT_ARRIVED.search(t) and _ORDER_WORDS.search(t) and not re.search(r"\brefund\b|\bpayment\b", t, _I):
        return Complaint.DELAYED_DELIVERY
    if _GENERAL.search(t) or _WANTS_HUMAN.search(t):
        return Complaint.GENERAL
    return None
