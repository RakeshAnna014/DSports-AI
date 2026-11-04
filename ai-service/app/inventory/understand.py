"""Business-question understanding: deterministic rules first (Tier 0, no model call), a cheap structured-output model only when the
rules cannot place a question. Output is an intent plus entities (sport, category, product, discounts, event, horizon, ...).

Rules never produce a number about inventory; they only decide WHICH tools to run. Write-style requests (change a price, create a
purchase order, launch a promotion, transfer stock) are recognised here and refused: Phase 2 is decision support.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import StrEnum


class Intent(StrEnum):
    OVERVIEW = "OVERVIEW"
    STOCK_RISK = "STOCK_RISK"
    REPLENISH = "REPLENISH"
    OVERSTOCK = "OVERSTOCK"
    SLOW_MOVING = "SLOW_MOVING"
    CLEARANCE = "CLEARANCE"
    PROMOTION = "PROMOTION"
    COMPARE_SCENARIOS = "COMPARE_SCENARIOS"
    EVENT = "EVENT"
    FORECAST = "FORECAST"
    REGION = "REGION"
    POLICY = "POLICY"
    QUALITY = "QUALITY"
    REFUSE_WRITE = "REFUSE_WRITE"
    BLOCKED = "BLOCKED"                 # stopped by the input guard (injection, credential or escalation attempt) before any model or tool
    INVALID_REQUEST = "INVALID_REQUEST" # an impossible input, e.g. a 95% discount
    UNKNOWN = "UNKNOWN"


@dataclass
class Entities:
    sport: str | None = None
    category: str | None = None
    product: str | None = None            # a product name or part of it
    discounts: list[float] = field(default_factory=list)
    invalid_discounts: list[float] = field(default_factory=list)   # percentages outside 1..70 (or negative): reported, never silently replaced by defaults
    older_than_days: int | None = None
    top: int | None = None
    within_days: int | None = None
    event_word: bool = False
    why: bool = False
    followup: bool = False                # "why?", "which is better?": relies on the previous turn
    region: bool = False
    slow_only: bool = False
    unknown_terms: list[str] = field(default_factory=list)   # capitalised names that match no product, sport, category, brand or region

    def to_json(self) -> dict:
        return {k: v for k, v in self.__dict__.items()}


@dataclass
class Understanding:
    intent: Intent
    entities: Entities
    method: str                            # rules | model | followup
    confidence: str = "high"


CATEGORY_WORDS: list[tuple[str, str]] = [
    (r"\bkit ?bags?\b|\bbackpacks?\b|\bduffels?\b|\bbags?\b", "Kit Bag"), (r"\bbats?\b|\bracquets?\b|\bracquet\b|\brackets?\b", "Bat"),
    (r"\bballs?\b|\bshuttlecocks?\b", "Ball"), (r"\bgloves?\b", "Gloves"), (r"\bshoes?\b|\bboots?\b|\bspikes\b|\bsneakers?\b", "Shoes"),
    (r"\bjerseys?\b|\bshirts?\b|\bt-shirts?\b", "Jersey"), (r"\bhelmets?\b", "Helmet"), (r"\baccessor(y|ies)\b", "Accessories"),
    (r"\bequipment\b", "Equipment"), (r"\bbikes?\b|\bbicycles?\b|\bcycles\b", "Bike"), (r"\bwearables?\b|\bwatch(es)?\b", "Wearables"),
]
_WRITE = re.compile(r"^\s*(please\s+)?(go ahead and\s+|can you\s+|could you\s+|just\s+)?(create|place|raise|submit|approve|launch|activate|apply|change|update(?!\s+(me|us)\b)|set|"
                    r"reduce|cut|execute|start|schedule|transfer|move|send|publish|order|delete|remove|retrain|overwrite|edit|modify|cancel|reset|increase|adjust|lower)\b[^?]*\b(price|prices|pricing|discount|"
                    r"promotion|sale|purchase order|po|orders?|stock|inventory|campaign|transfer|lead[- ]?time|suppliers?|moq|case pack|cost|costs|margin|margin floor|forecasts?|models?|"
                    r"safety stock|reorder point|thresholds?|records?|data|events?|policy|policies)\b", re.I)
_READONLY_STARTS = re.compile(r"^\s*(what|which|how|why|where|when|who|show|list|tell|do we|are we|is there|summari[sz]e|give me|compare)\b", re.I)
_DISCOUNT = re.compile(r"(\d{1,2})(?:\.\d+)?\s*(?:%|percent|per cent)", re.I)
_DISCOUNT_ANY = re.compile(r"(\d{1,3}(?:\.\d+)?)\s*(?:%|percent|per cent)", re.I)
_OLDER = re.compile(r"(?:older than|more than|over|beyond|longer than|for more than)\s+(\d{1,4})\s+days", re.I)
_TOP = re.compile(r"\btop\s+(\d{1,2})\b", re.I)
_EVENT = re.compile(r"\b(tournaments?|world cup|marathons?|olympics?|championships?|league|events?|open|premier|cup)\b", re.I)


def _find_sport(text: str, sports: list[str]) -> str | None:
    for sport in sorted(sports, key=len, reverse=True):          # "table tennis" before "tennis"
        if re.search(rf"\b{re.escape(sport.lower())}\b", text):
            return sport
    return None


def _find_category(text: str, categories: set[str]) -> str | None:
    for pattern, category in CATEGORY_WORDS:
        if category in categories and re.search(pattern, text):
            return category
    return None


_STOP = {"which", "what", "how", "why", "where", "when", "who", "show", "list", "tell", "give", "are", "is", "do", "does", "can", "could", "please", "and", "the", "for", "our",
         "we", "i", "poc", "sku", "skus", "moq", "sop", "po", "ai", "inr", "north", "south", "east", "west", "central", "january", "february", "march", "april", "may", "june",
         "july", "august", "september", "october", "november", "december", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "dsports"}
_CAP = re.compile(r"\b[A-Z][A-Za-z0-9]{2,}\b")


def unknown_terms(message: str, sports: list[str], categories: set[str], product_names: list[str]) -> list[str]:
    """Capitalised words (after the first) that are not part of any product name, sport, category or common word: probably a product we do not have."""
    known = {w.lower() for n in product_names for w in re.findall(r"[A-Za-z0-9]+", n)} | {s.lower() for s in sports for s in s.split()} | {c.lower() for c in categories for c in c.split()} | _STOP
    first_end = re.match(r"\s*\S+", message)
    body = message[first_end.end():] if first_end else message
    return [t for t in dict.fromkeys(_CAP.findall(body)) if t.lower() not in known and not t.lower().endswith("s") or (t.lower() not in known and t.lower().rstrip("s") not in known)]


def extract_entities(message: str, sports: list[str], categories: set[str], product_names: list[str]) -> Entities:
    text = message.lower()
    e = Entities()
    e.sport = _find_sport(text, sports)
    # a full product name wins over a loose category word
    for name in sorted(product_names, key=len, reverse=True):
        base = name.split(" - ")[0].lower()
        if name.lower() in text or (len(base) > 12 and base in text):
            e.product = name.split(" - ")[0] if name.lower() not in text else name
            break
    e.category = None if e.product else _find_category(text, categories)
    all_pct = [(-1.0 if text[max(0, m.start() - 2):m.start()].strip().endswith("-") else 1.0) * float(m.group(1)) for m in _DISCOUNT_ANY.finditer(text)]
    e.discounts = sorted({v for v in all_pct if 1 <= v <= 70})
    e.invalid_discounts = sorted({v for v in all_pct if v < 1 or v > 70})
    if m := _OLDER.search(text):
        e.older_than_days = int(m.group(1))
    if m := _TOP.search(text):
        e.top = int(m.group(1))
    if re.search(r"\bnext month\b|\b30 days\b|\bthis month\b", text):
        e.within_days = 30
    elif re.search(r"\bthis week\b|\bnext week\b|\b7 days\b|\bnext few days\b", text):
        e.within_days = 7
    elif re.search(r"\bnext quarter\b|\b90 days\b|\b3 months\b", text):
        e.within_days = 90
    e.event_word = bool(_EVENT.search(text)) and bool(re.search(r"\b(tournament|world cup|marathon|olympic|championship|league|event|upcoming)\b", text))
    e.why = bool(re.match(r"\s*(why|how come|what is driving|what's driving|what is causing)\b", text)) or bool(re.search(r"\bwhy\b", text))
    e.region = bool(re.search(r"\bregion|regional|region-wise|which (state|city|zone)\b", text))
    e.slow_only = bool(re.search(r"\bslow[- ]moving\b|\bslow movers?\b|\baged?\b|\bageing\b|\baging\b", text))
    e.unknown_terms = unknown_terms(message, sports, categories, product_names)
    return e


def classify(message: str, e: Entities) -> tuple[Intent, str]:
    text = message.lower().strip()
    if _WRITE.search(text) and not _READONLY_STARTS.match(text):
        return Intent.REFUSE_WRITE, "rules"
    if re.search(r"which (of (those|these|them|the two|the options|the scenarios)( scenarios| options| discounts)?|one|scenario|option|discount|promotion)( is| would be| gives| gives us| looks)? (better|best|preferable)|which (scenario|option|discount|promotion)\b.*\b(better|best|preferable|recommend)|\bbetter (scenario|option)\b|"
                 r"\bbest balance\b|\bcompare (the )?(scenarios|discounts|options)\b|\bwhich is better\b|\bwhich one is better\b|"
                 r"\bbest inventory reduction\b|which discount gives", text):
        return Intent.COMPARE_SCENARIOS, "rules"
    if re.search(r"\bpolic(y|ies)\b|\bminimum order\b|\bmoq\b|\bsop\b|\bprocedures?\b|\bapprov(al|e|es|ed|er)\b", text):
        return Intent.POLICY, "rules"          # "what is our clearance POLICY" is a knowledge question, not a request for candidates
    if e.discounts or e.invalid_discounts or re.search(r"\bwhat (happens|if)\b.*\b(discount|sale|promotion|off)\b|\b(discount|promotion|sale)\b.*\bscenario", text):
        return Intent.PROMOTION, "rules"
    if re.search(r"\bclearance|\bliquidat|\bclear (out|the)\b", text):
        return Intent.CLEARANCE, "rules"
    if e.event_word or re.search(r"\b(ready for|prepared for|enough .* for the)\b.*\b(tournament|event|marathon)\b", text):
        return Intent.EVENT, "rules"
    if re.search(r"\bwhere should we (move|send|transfer|redistribute)\b|\b(move|transfer|redistribute)\b.*\b(excess|surplus|stock|inventory)\b.*\b(to|between|across)\b|\brebalanc", text):
        return Intent.REGION, "rules"
    if re.search(r"\b(replenish|re-?order|restock|how much (should|do) we (order|buy)|what should we order|purchase)\b", text):
        return Intent.REPLENISH, "rules"
    if re.search(r"\bstock[- ]?out|\bstockout|\brun out\b|\bout of stock\b|\bat risk\b|\binventory risks?\b|\brisks?\b|\bsell(ing)? out\b|\bsold out\b|\brunning (low|short)\b", text):
        return Intent.STOCK_RISK, "rules"
    if re.search(r"\bslow[- ]moving\b|\bslow movers?\b|\bageing\b|\baging\b|\bsitting in inventory\b|older than|\bold stock\b", text) or e.older_than_days:
        return Intent.SLOW_MOVING, "rules"
    if re.search(r"\boverstock|\bexcess\b|\btoo much (stock|inventory)\b", text):
        return Intent.OVERSTOCK, "rules"
    if e.region or re.search(r"\bwhere should we move\b", text):
        return Intent.REGION, "rules"
    if re.search(r"\bpolic(y|ies)\b|\bminimum order\b|\bmoq\b|\bsop\b|\bprocedure\b|\bapproval\b|\bsupplier\b|\blead[- ]?times?\b", text):
        return Intent.POLICY, "rules"
    if re.search(r"\baccura|\bhow reliable\b|\bdata quality\b|\bconfidence\b|\bwhich model\b|\bmodel (do|did|is|are)\b|\bbacktest", text):
        return Intent.QUALITY, "rules"
    if re.search(r"\bforecast|\bdemand\b|\bwill (we )?sell\b|\bsales\b|\bselling\b", text):
        return Intent.FORECAST, "rules"
    if re.search(r"\bsummar|\boverview\b|\binventory health\b|\bmanagement\b|\bhow is (our )?inventory\b|\bstatus\b", text):
        return Intent.OVERVIEW, "rules"
    return Intent.UNKNOWN, "rules"


_FOLLOWUP = re.compile(r"^\s*(why( is| are| does| do)?( that| this| it| so)?\??|why\?|how so\??|explain( that| this)?\??|what('s| is) driving (that|this)\??|"
                       r"which (one )?is better\??|which (scenario|option) is better\??)\s*$", re.I)


_ELLIPSIS = re.compile(r"^\s*(and|what about|how about|same (question )?for|also|now)\b", re.I)


def understand(message: str, previous: dict | None, sports: list[str], categories: set[str], product_names: list[str]) -> Understanding:
    """`previous` = the entities + intent of the last turn (conversation memory) or None."""
    e = extract_entities(message, sports, categories, product_names)
    if previous and _FOLLOWUP.match(message):
        prev = Entities(**{k: v for k, v in previous.get("entities", {}).items() if k in Entities.__dataclass_fields__})
        prev.why, prev.followup = True, True
        intent = Intent(previous.get("intent", Intent.UNKNOWN))
        if re.search(r"better", message.lower()):
            intent = Intent.COMPARE_SCENARIOS
        return Understanding(intent, prev, "followup")
    if previous and _ELLIPSIS.match(message) and (e.sport or e.category or e.product) and previous.get("intent") not in (None, "UNKNOWN", "BLOCKED", "REFUSE_WRITE"):
        prev = previous.get("entities", {})
        e.within_days = e.within_days or prev.get("within_days")        # "and football?" keeps "next month"
        e.top = e.top or prev.get("top")
        e.slow_only = e.slow_only or bool(prev.get("slow_only"))
        if not e.discounts:
            e.discounts = [float(d) for d in prev.get("discounts", [])]
        return Understanding(Intent(previous["intent"]), e, "followup")
    intent, method = classify(message, e)
    # carry the subject over when the question names no sport/category/product ("and what about the gloves?")
    if previous and intent not in (Intent.OVERVIEW, Intent.REFUSE_WRITE) and not (e.sport or e.category or e.product):
        prev = previous.get("entities", {})
        if prev.get("sport") and intent in (Intent.COMPARE_SCENARIOS, Intent.PROMOTION, Intent.CLEARANCE, Intent.REPLENISH, Intent.EVENT, Intent.REGION, Intent.STOCK_RISK):
            if intent in (Intent.COMPARE_SCENARIOS, Intent.PROMOTION):
                e.sport, e.category = prev.get("sport"), prev.get("category")
                e.slow_only = e.slow_only or bool(prev.get("slow_only"))
    if previous and intent in (Intent.COMPARE_SCENARIOS, Intent.PROMOTION) and not e.discounts:
        e.discounts = [float(d) for d in previous.get("entities", {}).get("discounts", [])] or e.discounts
    return Understanding(intent, e, method)
