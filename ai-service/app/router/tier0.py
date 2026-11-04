"""Tier 0: deterministic routing. Zero model tokens.

A message is routed here only when a rule matches with confidence. Everything else goes to Tier 1 (a cheap model call that
classifies and extracts slots). Rules are intentionally narrow: a wrong rule is worse than a model call.
"""
from __future__ import annotations

import datetime as dt
import re

from app.router.extract import (BRANDS, CATEGORIES, SPORTS, extract_ordinals, extract_order_ref, extract_shopping_slots)
from app.router.intents import Intent, Route
from app.router.periods import parse_period
from app.store.state import ConversationState

_YES = re.compile(r"^\s*(yes|yep|yeah|yup|sure|ok|okay|confirm|confirmed|go ahead|do it|please do|proceed|absolutely|correct)\b[\s,.!]*(.*)$", re.I)
_BARE_YES = re.compile(r"^\s*(yes|yep|yeah|yup|sure|ok|okay|confirm|confirmed|go ahead|do it|please do|proceed|absolutely|correct)[\s.!]*$", re.I)
_NO = re.compile(r"^\s*(no|nope|nah|cancel|never ?mind|stop|don'?t|do not|abort)\b", re.I)
_GREETING = re.compile(r"^\s*(hi|hello|hey|good (morning|afternoon|evening)|thanks|thank you|thx|bye|goodbye)\b[\s!.,]*$", re.I)
_SHOP_VERB = re.compile(r"\b(need|want|looking for|look for|find|show me|show|recommend|suggest|buy|get me|searching|search|interested in|any|which|what .* (bat|shoes?|ball|gloves?)|options?)\b", re.I)
_REFINE = re.compile(r"\b(too (expensive|costly|pricey|much)|cheaper|less expensive|lower (price|budget)|reduce (the )?price|more affordable|"
                     r"lighter|heavier|something else|other (options|ones)|more options|different (brand|one)|show (me )?more|anything else)\b", re.I)
_COMPARE = re.compile(r"\b(compare|comparison|versus|vs\.?|difference between|differences between|side by side)\b", re.I)
_ADVICE = re.compile(r"\b(which (one )?(is|would be|should|do you|has|offers|gives|looks|weighs|costs)|(better|best) value|value for money|why should i (choose|buy|pick|go for)|which of (these|those|the)|best for|better for|suitable for|good for|recommend (me )?one|pick one|choose between|should i (buy|get|choose|go for))\b", re.I)
_KNOWLEDGE = re.compile(r"\bwhat (weight|size|grade|length|type of|kind of)\b[^.?!]{0,50}\b(should|do i|to)\b[^.?!]{0,30}\b(pick|choose|buy|get|use|go for)\b|"
                        r"\b(how (do|should|can|often|long) (i|you|we|to)|how to|ways to|tips? (for|on)|what is the best way to|steps? to)\b.*\b(maintain|care|clean|oil|store|knock|wash|dry|look after|use|choose|pick|size|fit|replace|repair)\b|"
                        r"\b(maintain|maintenance|care (for|of|guide|instructions)|knocking[- ]in|knock in|linseed|storage|looking after)\b|"
                        r"\b(buying guide|how to choose|how to pick|what size|which size)\b", re.I)
_DEFINITION = re.compile(r"\b(what is|what are|what'?s|explain|tell me about|meaning of|difference between|differences between)\b[^.?!]{0,60}"
                         r"\b(willow|kashmir|cane|grip|toe guard|sweet spot|pick ?up|grade \d|leather ball|tennis ball|cork|seam|studs?|sole|cleats?|blade|handle|edges)\b", re.I)
_POLICY = re.compile(r"\b(warranty|guarantee|return policy|refund policy|shipping policy|delivery (time|charges?|policy)|exchange policy|"
                     r"how long (do i have|is the|does)|return window|can i (return|exchange|cancel)|faq|what is your policy|how (do|does) (returns?|refunds?|shipping|delivery|cancellations?) work|how do i (return|cancel|get a refund|track)|"
                     r"(return|refund|shipping|delivery|cancellation)s? (policy|rules|terms|time|process))\b", re.I)
_SUPPORT_CASE = re.compile(r"\b(human|real person|live agent|customer (care|service|support) (agent|executive|representative|number|team)|support (ticket|case|request)|"
                           r"(raise|file|open|create|log) (a |an )?(ticket|complaint|case)|escalate|speak (to|with) (a|an|someone|somebody|your)|call me|talk to (a|an|someone|somebody))\b", re.I)
_CART = re.compile(r"\b(my|the|in my)\s+(cart|basket)\b|\b(show|view|see|check|open)\s+(me\s+)?(my\s+)?(cart|basket)\b", re.I)
_TRACK = re.compile(r"\b(where is|where'?s|track|tracking|locate|when (will|is|does)|has (my|the|it).*(shipped|arrived|been delivered|been dispatched)|"
                    r"(delivery|shipping|shipment|order) status|status of (my|the)? ?(order|delivery|shipment|package|parcel)|"
                    r"is (my|the) (order|package|parcel).*(shipped|delivered|on its way))\b", re.I)
_ORDERS_LIST = re.compile(r"\b(show|list|see|view|display|check|get)\b[^.?!]{0,30}\b(orders?|purchases|order history)\b|"
                          r"\bmy (recent |last |past |previous |latest )?(orders?|purchases)\b|\border history\b|"
                          r"\bwhat (did|have) i (order|buy|bought|purchase|ordered|purchased)\b|\bwhat (are|were) my (recent |last |past )?orders?\b", re.I)
_ORDER_DETAIL = re.compile(r"\b(details?|items?|contents?|what'?s in|what was in|what is in|summary|total)\b[^.?!]{0,30}\border\b|\border\b[^.?!]{0,20}\b(details?|items?|contents?|summary|total)\b", re.I)
_PAYMENT = re.compile(r"\bpayment\b[^.?!]{0,40}\b(status|go through|went through|succeed|successful|received|done|complete|fail|failed)\b|\bdid my payment\b|\bpayment status\b", re.I)
_REFUND = re.compile(r"\brefunds?\b", re.I)
# "What do the refund statuses mean?" asks about the policy/lifecycle, not about this customer's own refund.
_STATUS_MEANING = re.compile(r"\b(what (do|does)|explain|meaning of|difference between)\b[^.?!]{0,50}\b(statuses|states|stages)\b|"
                             r"\bwhat (is|are) the\b[^.?!]{0,20}\b(statuses|states|stages)\b|"
                             r"\b(statuses|status)\b[^.?!]{0,30}\b(mean|means|stand for)\b", re.I)
_REFUND_STATUS = re.compile(r"\b(status|where|has|have|processed|received|track|complete|completed|credited|any refund|my refunds?)\b", re.I)
_REFUND_WHY = re.compile(r"\b(why|how long|when will|when do|when should|haven'?t|have not|not (yet )?(received|got|arrived)|delay|taking (so )?long|still waiting)\b", re.I)
_RETURN_CHECK = re.compile(r"\b(can i|could i|am i able to|is it possible to|may i|eligible|eligibility|allowed to)\b[^.?!]{0,40}\b(return|send back|exchange)\b|\b(return|send back)\b[^.?!]{0,40}\b(eligible|eligibility|possible|allowed)\b|\bcan (these|those|it|this|them) be returned\b|\bcan i return\b", re.I)
_WHERE_IT = re.compile(r"^\s*(where|when)(\s+is|'s|\s+will)?\s+(it|that|this|my order|the order)\b[\s?!.]*(arrive|come|be delivered|delivered)?[\s?!.]*$", re.I)
_ACCOUNT_WORDS = re.compile(r"\b(my|mine|i|me|i'm|order|orders|ordered|bought|track|tracking|return|refund|cart|payment|delivered|shipment)\b", re.I)
_RETURN_DO = re.compile(r"\b(return|send back)\b", re.I)
_RETURN_WISH = re.compile(r"\b(want|need|wish|would like|like|going|have|got|trying) to (return|send back)\b|\b(return|send back) (my|the|this|that|these|those|it|them)\b", re.I)
_REORDER = re.compile(r"\b(re-?order|(order|buy|get) (it |them |this |that |these |those |the )?([a-z-]+ )?(again|once more)|same (one|ones|shoes|bat|ball|pair|thing|item)s? (again|as (last time|before))|add (the )?same|add (it|them|this|that) (to|in) (my )?cart|get (it|them|the same) again|order the same)\b", re.I)
_THEM = re.compile(r"\b(them|it|these|those|this|that|the shoes|the bat|the ball|the pair)\b", re.I)
_STRIP_LEADING_VERB = re.compile(r"\b(show|view|see|check)\b", re.I)


def classify(message: str, conv: ConversationState | None, *, authenticated: bool, today: dt.date | None = None,
             known_products: frozenset[str] | set[str] = frozenset()) -> Route | None:
    """Return a Route when a rule is confident, else None (=> Tier 1)."""
    text = message.strip()
    low = text.lower()
    today = today or dt.datetime.now(dt.timezone.utc).date()
    conv = conv or ConversationState("", "")

    if _GREETING.match(text):
        return Route(Intent.SMALLTALK, 0)
    if conv.pending_nonce:
        if _YES.match(text):
            return Route(Intent.CONFIRM_YES, 0)
        if _NO.match(text):
            return Route(Intent.CONFIRM_NO, 0)
    elif _BARE_YES.match(text) or _NO.match(text) and len(text.split()) <= 3:
        # A bare "yes"/"no" with nothing waiting is never an instruction to do something: answer it deterministically.
        return Route(Intent.CONFIRM_YES if _BARE_YES.match(text) else Intent.CONFIRM_NO, 0)

    ref = extract_order_ref(text)
    period = parse_period(text, today)
    candidate = conv.slots.get("return_candidate")

    support_route = _support_route(text, low, conv, ref, period)
    if support_route is not None:
        return support_route

    # ---- account intents (deterministic data lookups). Reorder is checked before the cart: "add the same shoes to my cart" is a reorder.
    if _REORDER.search(text):
        return Route(Intent.REORDER, 0, {"product_hint": _hint(low), **ref})
    if _CART.search(text) and not _SHOP_VERB.search(_STRIP_LEADING_VERB.sub("", low)):
        return Route(Intent.CART_VIEW, 0)
    if _STATUS_MEANING.search(text):
        return Route(Intent.SUPPORT_POLICY, 0, {"question": text})
    if _REFUND.search(text) and not _POLICY.search(text):
        if _REFUND_WHY.search(text):
            return Route(Intent.REFUND_WHY, 0, ref)
        if _REFUND_STATUS.search(text):
            return Route(Intent.REFUND_STATUS, 0, ref)
    if _PAYMENT.search(text):
        return Route(Intent.PAYMENT_STATUS, 0, ref)
    if _RETURN_CHECK.search(text):
        return Route(Intent.RETURN_CHECK, 0, {"product_hint": _hint(low), **ref})
    if _RETURN_DO.search(text) and candidate and _THEM.search(text) and not _POLICY.search(text):
        return Route(Intent.RETURN_CREATE, 0, {"reason": _reason(text), "quantity": _quantity(low)})
    if _RETURN_WISH.search(text) and not _POLICY.search(text):
        # "I want to return my last order": check eligibility first; nothing is created without the customer's confirmation
        return Route(Intent.RETURN_CHECK, 0, {"product_hint": _hint(low), **ref})
    if _WHERE_IT.match(text) and conv.last_order:
        return Route(Intent.TRACK_ORDER, 0, {})        # "where is it?" right after an order was shown: that order
    if _TRACK.search(text) and (ref or re.search(r"\b(order|package|parcel|shipment|delivery)\b", low)):
        return Route(Intent.TRACK_ORDER, 0, ref)
    if _ORDER_DETAIL.search(text) and ref:
        return Route(Intent.ORDER_DETAIL, 0, ref)
    if _ORDERS_LIST.search(text) and not ref.get("order_number"):
        slots = {"since": period.since.isoformat(), "until": period.until.isoformat(), "period_label": period.label} if period else {}
        if ref.get("latest") and "orders" not in low:   # "my recent orders" is a list; "my latest order" is one order
            slots["latest"] = True
        return Route(Intent.ORDERS_LIST, 0, slots)

    # ---- a request to raise a complaint / reach support that no earlier rule recognised: the real complaint workflow
    if _SUPPORT_CASE.search(text):
        return Route(Intent.COMPLAINT, 0, {"complaint": "GENERAL"})

    # ---- comparing products the customer names (no prior search needed)
    named = sorted((n for n in known_products if len(n) >= 6 and n in low), key=low.find)
    if _COMPARE.search(text) and len(named) >= 2:
        return Route(Intent.COMPARE, 0, {"product_refs": named[:5]})

    # ---- a recommendation about the product being discussed: the scenario is chosen by rules, never by a model.
    # "Similar / alternative" is deliberately NOT routed here: the product-context route below already serves it (alternative search).
    scenario = reco_scenario(text)
    if scenario and scenario != "SIMILAR" and conv.selected_product:
        budget = extract_shopping_slots(text).get("max_price")
        return Route(Intent.RECOMMEND, 0, {"scenario": scenario, **({"budget": budget} if budget else {})})

    # ---- the product in context: the one chosen with "Ask AI", or a shown product the customer names ("the MRF one")
    in_context = _product_context_route(text, low, conv)
    if in_context is not None:
        return in_context

    # ---- a whole set-up, or "what should I get?": built from the sport's kit and the customer's own history, no model needed
    kit_slots = extract_shopping_slots(text)
    if _KIT_BUILD.search(text) and kit_slots.get("sport") and "category" not in kit_slots:
        return Route(Intent.KIT_BUILD, 0, {k: v for k, v in kit_slots.items() if k in ("sport", "max_price")})
    if _RECOMMEND.search(text) and "category" not in kit_slots:
        if kit_slots.get("sport"):
            return Route(Intent.KIT_BUILD, 0, {k: v for k, v in kit_slots.items() if k in ("sport", "max_price")})
        return Route(Intent.RECOMMEND, 0, {})

    # ---- knowledge / policy (retrieval, answered with citations)
    if _POLICY.search(text):
        return Route(Intent.SUPPORT_POLICY, 0, {"question": text})
    if _KNOWLEDGE.search(text) or _DEFINITION.search(text):
        return Route(Intent.PRODUCT_KNOWLEDGE, 0, {"question": text})

    # ---- shopping
    slots = extract_shopping_slots(text)
    if conv.last_products and (_COMPARE.search(text) or _ADVICE.search(text)):
        # "Which swimming goggles are best for beginners?" right after gym equipment names a NEW sport/category: that is a fresh search,
        # not a question about the products on screen.
        shown_sports = {p.get("sport") for p in conv.last_products if p.get("sport")}
        shown_categories = {p.get("category") for p in conv.last_products if p.get("category")}
        new_topic = (slots.get("sport") and shown_sports and slots["sport"] not in shown_sports) or (slots.get("category") and shown_categories and slots["category"] not in shown_categories)
        if new_topic and not _THIS.search(low):
            return Route(Intent.PRODUCT_SEARCH, 0, {**slots, "query": None})
        intent = Intent.COMPARE if _COMPARE.search(text) else Intent.PRODUCT_ADVICE
        return Route(intent, 0, {"ordinals": extract_ordinals(text), **slots})
    if conv.last_products and _REFINE.search(text):
        feedback = "lower" if re.search(r"expensive|costly|pricey|cheaper|less expensive|lower|affordable|too much", low) else None
        refine = {"price_feedback": feedback}
        if re.search(r"\bheavier\b", low):
            refine["weight_pref"] = "heavy"
        return Route(Intent.REFINE_SEARCH, 0, {**slots, **{k: v for k, v in refine.items() if v}})
    if ("category" in slots or "sport" in slots or "brand" in slots) and _SHOP_VERB.search(text) and not _COMPARE.search(text):
        return Route(Intent.PRODUCT_SEARCH, 0, {**slots, "query": None})
    if _is_plain_catalog_query(text, slots):
        # "cricket bat", "nike shoes": a keyword search. Filters come from the words, the answer is the deterministic result list.
        return Route(Intent.PRODUCT_SEARCH, 0, {**slots, "query": _leftover_words(low) or None, "plain": True})
    return None


# ---------------------------------------------------------------------------------------------------- support cases and complaints
_CASE_REF = re.compile(r"\bCASE-\d{8}-\d{6}\b", re.I)
_CASE_NOUN = r"(?:cases?|complaints?|tickets?)"
_CASE_LIST = re.compile(rf"\b(?:show|list|see|view|check|display|get)\b[^.?!]{{0,25}}\b(?:my|all|the)\b[^.?!]{{0,18}}\b{_CASE_NOUN}\b|^\W*my (?:open |support |active |recent |pending )?{_CASE_NOUN}\W*$", re.I)
_CASE_STATUS = re.compile(rf"\bwhat(?:'s| is| has| have)? ?(?:happened|become|going on|the status)\b[^.?!]{{0,25}}\b(?:my|the)\b[^.?!]{{0,12}}\b{_CASE_NOUN}\b|"
                          rf"\bstatus (?:of|on) (?:my|the)\b[^.?!]{{0,12}}\b{_CASE_NOUN}\b|\b{_CASE_NOUN} (?:status|update|progress)\b|"
                          rf"\b(?:any|an?) (?:update|news|progress|response|reply|answer)\b[^.?!]{{0,25}}\b{_CASE_NOUN}\b|\bhas (?:anyone|someone|support|the team)\b[^.?!]{{0,30}}\b(?:responded|replied|looked|picked|taken|seen)\b|"
                          rf"\bany (?:update|news) on (?:it|that|this)\b|\bwhere (?:is|are) my {_CASE_NOUN}\b|"
                          rf"\b(?:latest|newest|current)\b[^.?!]{{0,20}}\b(?:on|about|with|for|of)\b[^.?!]{{0,15}}\b{_CASE_NOUN}\b", re.I)
_CASE_REOPEN = re.compile(rf"\breopen\b[^.?!]{{0,20}}\b{_CASE_NOUN}\b|\b{_CASE_NOUN}\b[^.?!]{{0,30}}\b(?:is |was )?(?:not (?:solved|resolved|fixed)|still (?:not|open|an issue|broken|unresolved))\b", re.I)
_CASE_MESSAGE = re.compile(rf"\b(?:add|send|post|write|attach|put)\b[^.?!]{{0,25}}\b(?:message|note|comment|update|details?|info(?:rmation)?|photo|reply)\b[^.?!]{{0,25}}\b(?:to |on |for )?(?:my |the )?{_CASE_NOUN}\b|"
                           rf"\bupdate (?:my |the )?{_CASE_NOUN}\b|\b(?:tell|inform|message|reply to|write to) (?:the )?(?:support|support team|team)\b", re.I)
_CASE_ESCALATE = re.compile(r"\bescalate\b|\b(?:speak|talk|chat|connect|transfer)\b[^.?!]{0,15}\b(?:to|with)\b[^.?!]{0,10}\b(?:a |an |the |your )?(?:human|person|agent|someone|somebody|manager|supervisor|representative|executive|support|customer (?:care|service|support))\b|"
                            r"\bwant (?:a |to speak to a )?(?:manager|supervisor|human|real person)\b|\b(?:human|person) (?:to )?(?:review|look|check)\b|\bcall me\b", re.I)
_RAISE_IT = re.compile(r"^\W*(?:yes\W*)?(?:please\W*)?(?:go ahead and )?(?:do )?(?:raise|file|create|open|log|submit|register|make)\b[^.?!]{0,14}\b(?:it|that|this|one|a (?:support )?(?:case|complaint|ticket)|the (?:support )?(?:case|complaint|ticket))\b[^.?!]{0,25}$", re.I)
_DISPUTE_TEXT = re.compile(r"\b(?:that'?s|this is|it'?s) (?:wrong|not (?:right|true|correct|acceptable|fair|good enough))\b|\bi (?:disagree|dispute|don'?t accept|do not accept)\b|\bunacceptable\b|"
                           r"\byou(?:'re| are) wrong\b|\b(?:this|that) (?:doesn'?t|does not) help\b|\bnot satisfied\b", re.I)
_CORRECTION = re.compile(r"^\W*(?:no\b|nope|not (?:that|this) one|it was\b|it'?s\b|i meant\b|i mean\b|actually\b|the other\b|a different\b|wrong order|that'?s not\b)", re.I)
_AWAITING = {"awaiting_order", "awaiting_product", "awaiting_details"}


def _complaint_slots(low: str, ref: dict, period) -> dict:
    slots: dict = dict(ref)
    if slots.get("latest") and not re.search(r"\b(?:last|latest|most recent|newest|previous|recent)\b", low):
        slots.pop("latest")                                # "my order" is not "my latest order": the complaint flow narrows the customer's orders by what is plausible and asks if unclear
    if (vocab := _HINT_VOCAB.search(low)):                 # only a real product word counts: "request" or "wrong" in "return request" / "wrong size" are not products
        slots["product_hint"] = vocab.group(1)
    if period:
        slots.update({"since": period.since.isoformat(), "until": period.until.isoformat(), "period_label": period.label})
    return slots


def _support_route(text: str, low: str, conv: ConversationState, ref: dict, period) -> Route | None:
    """Support cases and complaints. A case id in the text is only ever a lookup key: the backend decides whether it is the customer's."""
    from app.support.classify import classify as classify_complaint, safety_flag
    state = conv.slots.get("support") or {}
    stage = state.get("stage")
    if conv.pending_nonce and stage == "case_proposed" and _RAISE_IT.match(text):
        return Route(Intent.CONFIRM_YES, 0)                      # "please raise it" answers the confirmation that is waiting
    if (m := _CASE_REF.search(text)):
        return Route(Intent.CASE_STATUS, 0, {"case_ref": m.group(0).upper()})
    if stage == "awaiting_case_message" and not conv.pending_nonce and not _NO.match(text):
        return Route(Intent.CASE_MESSAGE, 0, {"text": text})
    found = classify_complaint(text)
    if stage in _AWAITING and not conv.pending_nonce and not _BARE_YES.match(text):
        if found is not None:
            return Route(Intent.COMPLAINT, 0, {"complaint": found.value, **_complaint_slots(low, ref, period)})
        elsewhere = _ORDERS_LIST.search(text) or _TRACK.search(text) or _CART.search(text) or _POLICY.search(text) or _REORDER.search(text) or _RETURN_CHECK.search(text)
        if not elsewhere and (ref or _HINT_VOCAB.search(low) or period or len(text.split()) <= 14) and "?" not in text:
            return Route(Intent.COMPLAINT, 0, {"followup": True, **_complaint_slots(low, ref, period)})
    if _CASE_LIST.search(text):
        return Route(Intent.CASE_STATUS, 0, {"list": True, "unresolved_only": bool(re.search(r"\b(open|active|pending|unresolved|current)\b", low))})
    if _CASE_REOPEN.search(text):
        return Route(Intent.CASE_REOPEN, 0, {"text": text})
    mentions_case = bool(re.search(_CASE_NOUN, text, re.I))
    if _CASE_MESSAGE.search(text) and (found is None or mentions_case):          # a complaint that says "tell the team" is still a complaint
        return Route(Intent.CASE_MESSAGE, 0, {"text": text} if len(text.split()) > 9 else {})
    if _CASE_STATUS.search(text):
        return Route(Intent.CASE_STATUS, 0, {})
    if stage in ("investigated", "case_proposed") and not conv.pending_nonce:
        if _CORRECTION.match(text) and (ref or _HINT_VOCAB.search(low) or period):
            return Route(Intent.COMPLAINT, 0, {"followup": True, "correction": True, **_complaint_slots(low, ref, period)})   # "it was the running shoes": another order
        if _RAISE_IT.match(text):
            return Route(Intent.CASE_CREATE, 0, {})
        if _DISPUTE_TEXT.search(text):
            return Route(Intent.COMPLAINT, 0, {"followup": True, "dispute": True})
    if _CASE_ESCALATE.search(text) and (found is None or mentions_case):
        return Route(Intent.CASE_ESCALATE, 0, {"text": text} if len(text.split()) > 9 else {})
    if found is not None:
        return Route(Intent.COMPLAINT, 0, {"complaint": found.value, "safety": safety_flag(text) or None, **_complaint_slots(low, ref, period)})
    return None


# Recommendation scenarios, most specific first. "Premium" is only chosen when the customer asks for it.
_SCENARIOS: list[tuple[str, re.Pattern[str]]] = [
    ("FREQUENTLY_BOUGHT_TOGETHER", re.compile(r"\b(frequently bought|(bought|buy|buys|purchased?) together|often bought|(people|customers|others|shoppers) (also |usually |often )?(buy|bought|purchase\w*))\b", re.I)),
    ("UPGRADE", re.compile(r"\b(premium|upgrades?|higher[- ]end|top[- ]end|more expensive|step up|better (one|option|version|model|alternative)s?)\b", re.I)),
    ("SIMILAR", re.compile(r"\b(similar|alternatives?|comparable|something like (this|it)|other options|cheaper (one|option|alternative)s?)\b", re.I)),
    ("COMPLEMENTARY", re.compile(r"\b(accessor(y|ies)|add-?ons?|complement\w*|what (goes|pairs|works) (well )?with|goes? with (this|it)|to go with|goes along)\b", re.I)),
]


def reco_scenario(text: str) -> str | None:
    """The recommendation scenario a message asks for, or None. Pure rules, so the choice is testable and never depends on a model."""
    return next((name for name, pattern in _SCENARIOS if pattern.search(text)), None)


_KIT_BUILD = re.compile(r"\b(build|create|put together|assemble|set ?up|complete|start)\b[^.?!]{0,40}\b(home gym|gym|kit|set-?up|starter (set|kit))\b", re.I)
_RECOMMEND = re.compile(r"\b(recommend|suggest)\b\s+(me\s+)?(something|anything)\b|\b(recommend|suggest)\b[^.?!]{0,12}\bfor me\b|\bwhat (goes|pairs) (well )?with\b|"
                        r"\bwhat else (do i|should i) (need|get|buy)\b|\bcomplete (my|the|this) (set-?up|kit)\b", re.I)
_THIS = re.compile(r"\b(it|its|this|that|this (bat|product|one|item|shoes?|ball|pair|racket)|the product)\b", re.I)
_REVIEWS = re.compile(r"\b(reviews?|ratings?|rated|what (do|are) (people|customers|buyers|others) (say|saying|think)|feedback)\b", re.I)
_ALT = re.compile(r"\b(alternatives?|similar|something (cheaper|lighter)|anything (cheaper|lighter|similar)|cheaper|lighter|other options?|instead|different one)\b", re.I)
_PLURAL_REF = re.compile(r"\b(these|those|them|all of)\b", re.I)
_DETAIL = re.compile(r"\b(tell me more|more (about|info|information|details?)|know more|details? (of|about|on)|specs?|specifications?)\b|"
                     r"\b(how (heavy|much does (it|this) weigh)|weigh\w*|weight|made (of|from|out of)|material|dimensions?|how (big|long|tall)|"
                     r"how much|price|cost|in stock|available)\b", re.I)
_OVERVIEW = re.compile(r"\b(tell me more|more (about|info|information|details?)|know more|details? (of|about|on)|specs?|specifications?)\b", re.I)
_MANUFACTURER = re.compile(r"\b(manufacturer|recommended (playing )?conditions|playing conditions|who makes)\b", re.I)
_QUESTION_START = re.compile(r"^\W*(is|are|does|do|can|could|will|would|should|what|which|how|why)\b", re.I)
_ORDINAL_WORDS = (("first", 0), ("1st", 0), ("second", 1), ("2nd", 1), ("third", 2), ("3rd", 2))
_COMPARE_OTHER = re.compile(r"\b(?:with|to|against|and|vs\.?|versus)\s+(?:the\s+)?(.+?)[\s?.!]*$", re.I)


def _mentioned_product(low: str, conv: ConversationState) -> dict | None:
    """A product among those just shown that the customer points at ("the MRF one", "the first one"). Server-side data only."""
    shown = conv.last_products
    if not shown:
        return None
    for word, idx in _ORDINAL_WORDS:
        if re.search(rf"\b{word}\b", low) and idx < len(shown) and re.search(r"\b(one|option|product|bat|pair|item|ball|racket)\b", low):
            return shown[idx]
    hits = [p for p in shown if re.search(rf"\b{re.escape(p['name'].split()[0].lower())}\b", low)]
    return hits[0] if len(hits) == 1 else None


def _product_context_route(text: str, low: str, conv: ConversationState) -> Route | None:
    """Questions about ONE product. The product id always comes from conversation state (a product DSports returned), never from the
    customer's or a model's text. No model is needed to understand these; the answers are live DSports facts or cited RAG."""
    sel = conv.selected_product
    mentioned = _mentioned_product(low, conv)
    target = mentioned or sel
    if target is None:
        return None
    pronoun = bool(_THIS.search(low))
    if _COMPARE.search(text) and sel and pronoun:
        other = _COMPARE_OTHER.search(text)
        if other:
            return Route(Intent.COMPARE, 0, {"with_selected": True, "product_refs": [other.group(1).strip()]})
        return None
    if _REVIEWS.search(low) and (pronoun or mentioned or len(low.split()) <= 6):
        return Route(Intent.PRODUCT_REVIEWS, 0, {"product_id": target["id"]})
    if sel and _ALT.search(low) and not _PLURAL_REF.search(low) and not mentioned and not re.search(r"\bwhich\b", low):   # "which is lighter?" compares
        return Route(Intent.PRODUCT_SEARCH, 0, {"alternative_to": sel["id"], "cheaper": bool(re.search(r"cheap|afford|less expensive|lower", low)),
                                                 "lighter": bool(re.search(r"light", low))})
    if _KNOWLEDGE.search(text) or _DEFINITION.search(text):
        return None                                   # a general question ("what is English willow?"): handled as knowledge below
    if _DETAIL.search(low) and (pronoun or mentioned or (len(low.split()) <= 8 and _QUESTION_START.search(low))):
        return Route(Intent.PRODUCT_DETAIL, 0, {"product_id": target["id"], "overview": bool(_OVERVIEW.search(low))})
    if sel and not mentioned and (_MANUFACTURER.search(low) or (pronoun and (_QUESTION_START.search(low) or text.rstrip().endswith("?")))):
        return Route(Intent.PRODUCT_KNOWLEDGE, 0, {"question": text, "about_selected": True})
    return None


_FILLER = {"a", "an", "the", "some", "any", "for", "of", "and", "in", "to", "me", "my", "please", "pls", "with", "on"}


def _leftover_words(low: str) -> str:
    """The words that are not a sport, category or brand: they still help the keyword search ("running" in "running shoes")."""
    known = set(SPORTS) | set(CATEGORIES) | {w for b in BRANDS for w in b.split()}
    return " ".join(w for w in re.findall(r"[a-z0-9]+", low) if w not in known and w not in _FILLER)


def _is_plain_catalog_query(text: str, slots: dict) -> bool:
    """A short, question-free keyword query naming only a sport/category/brand: no model is needed to understand or to explain it."""
    if "?" in text or len(text.split()) > 5 or _ACCOUNT_WORDS.search(text):
        return False
    if not (slots.keys() & {"category", "sport", "brand"}):
        return False
    return not (slots.keys() & {"skill_level", "age_group", "weight_pref", "max_price", "min_price"})


_HINT_VOCAB = re.compile(r"\b(shoes?|boots?|spikes|bats?|balls?|gloves?|helmets?|jerseys?|bags?|rackets?|racquets?|pads?|shorts|socks|caps?|"
                         r"cricket|football|soccer|tennis|badminton|volleyball|basketball|mrf|ss|sg|dsc|nike|adidas|puma|kookaburra|spartan)\b")
_HINT_GENERIC = re.compile(r"\b(?:return|returning|send back|re-?order|order|buy|bought|ordered|purchased)\s+(?:again\s+)?"
                           r"(?:(?:the|my|these|those|same|a|an)\s+)*([a-z][a-z-]{2,})")
_NOT_A_PRODUCT_NOUN = {"it", "them", "this", "that", "these", "those", "one", "ones", "item", "items", "product", "products", "order", "orders",
                       "purchase", "again", "same", "last", "latest", "previous", "recent", "something", "anything", "everything", "from", "for", "with", "and",
                       "eligible", "eligibility", "possible", "allowed", "returnable", "able", "valid", "still", "within", "delivered", "shipped", "placed", "paid", "confirmed"}


def _hint(low: str) -> str | None:
    """The product the customer named ("shoes", "football", "umbrella"), or None if they named nothing. An unrecognised noun still counts:
    it must lead to "I couldn't find that in your orders", never to a guess at the latest purchase."""
    if m := _HINT_VOCAB.search(low):
        return m.group(1)
    g = _HINT_GENERIC.search(low)
    return g.group(1) if g and g.group(1) not in _NOT_A_PRODUCT_NOUN else None


def _quantity(low: str) -> int | None:
    m = re.search(r"\b(\d{1,2})\s*(?:x|units?|pairs?|pieces?|of them)\b", low)
    return int(m.group(1)) if m else None


def _reason(text: str) -> str | None:
    m = re.search(r"\b(?:because|since|as|reason(?: is|:)?)\s+(.{3,200})$", text, re.I)
    return m.group(1).strip().rstrip(".") if m else None
