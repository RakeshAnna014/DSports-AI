"""Step 1 of the topic gate: free, deterministic rules. No model, no network, no tokens.

The order is fixed and is part of the policy:

1. A DENY (OFF_TOPIC) rule ALWAYS wins over everything else, including conversation context and allow words ("who won the football world cup"
   contains "football" and is still off topic). Only when a *clause* hits a deny rule is the message split: the clauses that do not hit one are
   forwarded unchanged if they carry a real DSports signal (a mixed message), otherwise the whole message is off topic. A message that hits no deny
   rule is never split and never rewritten, so "a bat for a 12 year old and under 3000" stays one shopping query.
2. BUSINESS rules (sales, revenue, margins, warehouse stock numbers, forecasts, suppliers, other customers). "Is X in stock" is NOT business.
3. STRONG allow rules: sports/category/brand words, orders and returns vocabulary, booking vocabulary, cricket rules and the customer's own cricket data.
4. Otherwise the message is UNSURE (step 2 of the gate, the label call, decides). A caller may pass ``has_context=True`` (a confirmation is pending, a
   support flow is open, products are on screen): that only turns an UNSURE message into allowed. It never overrides rules 1 and 2.

Weak words ("price", "size", "store", "hero", "mat", "pool"...) do not allow a message on their own, because each of them also occurs in off-topic
questions ("the cost of living in Delhi"). They fall through to UNSURE.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from app.guard.topic import Topic
from app.router.extract import BRANDS, CATEGORIES, SPORTS


@dataclass(frozen=True)
class Verdict:
    topic: Topic | None                 # None: the rules cannot tell (UNSURE), unless ``context`` is set
    rule: str | None = None
    forward: str | None = None          # a mixed message: the part that is passed on, exactly as the customer wrote it
    context: bool = False               # allowed only because the conversation has live context (the message itself said nothing decisive)

    @property
    def mixed(self) -> bool:
        return self.forward is not None

    @property
    def unsure(self) -> bool:
        return self.topic is None and not self.context


def _re(*parts: str) -> re.Pattern[str]:
    return re.compile("|".join(parts), re.I)


# ---------------------------------------------------------------------------------------------------------------- DENY (off topic)
_OWN = re.compile(r"\b(my|our|mera|meri|mere)\b[^.?!]{0,25}\b(cases?|complaints?|orders?|tickets?|bookings?|refunds?|payments?|returns?|matches|match|teams?|"
                  r"stats|cart|account|delivery|shipment|package|tournaments?|league|innings|score|scorecard)\b", re.I)
_CODE_SAFE = re.compile(r"\b(promo|coupon|voucher|referral|discount|gift|offer|pin|zip|postal|qr|dress|area|country|tracking|order|otp) ?(code|codes)\b|\bcode of conduct\b", re.I)

# (name, pattern, own-data exemption applies, safe-word exemption)
_DENY: list[tuple[str, re.Pattern[str], bool, re.Pattern[str] | None]] = [
    ("code", _re(r"\b(css|html|javascript|typescript|python|java|sql|flexbox|regex|kotlin|golang|php|jquery|nodejs|node\.js|django|kubernetes|pandas|numpy|reactjs|angular)\b",
                 r"(?<!\w)c\+\+|(?<!\w)c#", r"\breact (component|hook|hooks|app|js)\b", r"\b(stack ?trace|segfault|compile error|null pointer|syntax error|unit tests?)\b"), False, None),
    ("code_action", _re(r"\b(write|create|generate|fix|debug|explain|make|build|implement|refactor)\b[^.?!]{0,30}\b(code|program|script|function|class|query|algorithm|website|web page|component|bug)\b"),
     False, _CODE_SAFE),
    ("knowledge", _re(r"\bcapital (city )?of\b", r"\b(who|what) (is|was|were) (the )?(current )?(president|prime minister|chief minister|ceo|king|queen|founder|inventor|author)\b",
                      r"\bwho (invented|discovered|wrote|painted|founded|composed|directed|sang)\b", r"\b(population|gdp|currency|national (anthem|bird|flower|animal)|time zone) of\b",
                      r"\bhow many (countries|continents|planets|states|people live|days in)\b",
                      r"\bwhat is the (largest|tallest|longest|smallest|oldest|deepest|highest) (country|city|river|mountain|ocean|animal|building|planet|lake|desert)\b",
                      r"\b(black holes?|photosynthesis|quantum|relativity|big bang|world war|climate change|inflation|democracy|blockchain|artificial intelligence|machine learning|chatgpt)\b",
                      r"\bwhat (time|day|date) is it\b", r"\bmeaning of life\b", r"\bsquare root\b"), False, None),
    ("math", _re(r"\b(solve|calculate|compute|evaluate|simplify)\b[^.?!]{0,25}\b(equation|integral|derivative|expression|polynomial|matrix|limit)\b",
                 r"\bwhat(?:'s| is) \d+(?:\.\d+)?\s*[-+*/x×÷^]\s*\d+", r"\b(cube root|factorial|prime numbers?|pythagoras|quadratic|trigonometry|calculus|algebra|geometry)\b",
                 r"\bsolve\b[^.?!]{0,12}\d+\s*[a-z]\s*[-+=]"), False, None),
    ("homework", _re(r"\b(homework|assignment|essay|thesis|dissertation|term paper|lab report|school project|exam (questions?|paper)|syllabus|ncert)\b",
                     r"\b(translate|translation)\b[^.?!]{0,30}\b(to|into|in)\b[^.?!]{0,15}\b(hindi|english|french|spanish|german|telugu|tamil|kannada|marathi|bengali|urdu|arabic|chinese|japanese)\b",
                     r"\b(summari[sz]e|paraphrase|proofread|rewrite)\b[^.?!]{0,25}\b(this|the|my)\b[^.?!]{0,15}\b(article|text|paragraph|essay|passage|document|email|letter)\b"), False, None),
    ("creative", _re(r"\b(write|compose|tell|give|make|sing|recite)\b[^.?!]{0,12}\b(a|an|me a|me an|some)\b[^.?!]{0,10}\b(poem|story|song|joke|jokes|riddle|haiku|limerick|speech|cover letter|resume|essay|lyrics|rap)\b",
                     r"\b(joke|jokes|riddle|horoscope|zodiac|astrology)\b"), False, None),
    ("news_entertainment", _re(r"\b(latest|breaking|today'?s|sports|cricket|football|world|national) news\b", r"\bnews headlines\b|\bheadlines\b",
                               r"\b(election|elections|politic(s|ian|ians|al)|parliament|stock market|sensex|nifty|bitcoin|crypto(currency)?|ethereum)\b",
                               r"\b(movies?|films?|web series|netflix|bollywood|hollywood|celebrity|celebrities|recipes?|biryani recipe)\b",
                               r"\bweather\b|\bwill it rain\b|\btemperature (today|tomorrow|in)\b", r"\bshare price of\b"), True, None),
    ("sports_trivia", _re(r"\bwho (won|wins|will win|is winning|scored the most|took the most)\b", r"\bkaun (jeeta|jitega|jita)\b",
                          r"\b(world cup|ipl|bbl|psl|premier league|la liga|bundesliga|serie a|champions league|europa league|olympics?|commonwealth games|asia cup|ashes|wimbledon|us open|french open|australian open|nba|nfl|fifa|uefa|icc|bcci)\b"
                          r"[^.?!]{0,50}\b(winner|winners|won|champions?|final|finals|score|scores|result|results|schedule|fixtures?|squad|points table|standings|record|records|history|trophy|auction|prize|match|matches)\b",
                          r"\b(highest|lowest|fastest|slowest|most|best|top) (individual |team |career )?(score|scores|runs|wickets|goals|centuries|hundreds|sixes|catches|records?)\b[^.?!]{0,20}\b(in|ever|of all time|in history)\b",
                          r"\b(best|greatest|top|richest|fastest|oldest|youngest|tallest) (batsman|batter|bowler|player|cricketer|footballer|captain|team|coach|all-?rounder|goalkeeper|striker|sportsperson|athlete)s?\b[^.?!]{0,25}\b(ever|of all time|in the world|in history|of the year|today|currently|right now|in india)\b",
                          r"\b(who is|who was|how old is|how tall is|net worth of|biography of|wife of|age of|family of|salary of)\b[^.?!]{0,25}\b(virat|kohli|dhoni|sachin|tendulkar|messi|ronaldo|rohit|bumrah|neymar|federer|djokovic|nadal|lebron|jordan|sindhu|neeraj|mbappe|haaland|sehwag|gavaskar|kapil dev|ganguly|dravid|ashwin|jadeja|pant|hardik)\b",
                          r"\b(virat|kohli|dhoni|sachin|tendulkar|messi|ronaldo|rohit sharma|bumrah)(?:'s)?\b[^.?!]{0,15}\b(wife|age|net worth|biography|girlfriend|salary|retire|retirement)\b",
                          r"\b(cricket|football|sports?|ipl) (news|gossip|rumou?rs?)\b", r"\b(history|origin|origins) of (the )?(cricket|football|tennis|badminton|olympics|ipl|world cup)\b",
                          r"\bwhen is the next (ipl|world cup|olympics|premier league|test|odi|t20)\b", r"\bwho is the (captain|coach|owner)\b[^.?!]{0,15}\b(of|for) (india|australia|england|pakistan|csk|rcb|mi|kkr|team india|real madrid|barcelona|manchester|chelsea|liverpool|arsenal)\b"),
     True, None),
    ("life_advice", _re(r"\b(diet plan|meal plan|medical advice|diagnos(e|is)|symptoms? of|medicine for|prescri(be|ption)|lawyer|legal advice|tax (filing|advice)|income tax|relationship advice|visa (application|process))\b",
                        r"\binvest(ing|ment)? (advice|tips)\b"), False, None),
]

# ---------------------------------------------------------------------------------------------------------------- BUSINESS
_BUSINESS: list[tuple[str, re.Pattern[str]]] = [
    ("finance", _re(r"\b(revenue|turnover|profit(s|ability)?|ebitda|cogs|cost of goods|gross margin|net margin|profit margins?|margins?|landed cost|cost price|markups?|wholesale (price|cost|rate)|payroll|headcount|attrition)\b")),
    ("sales", _re(r"\b(total|monthly|weekly|daily|quarterly|yearly|annual|today'?s|yesterday'?s|last (week|month|quarter|year)'?s?|this (week|month|quarter|year)'?s?|our|company|store|branch|regional|overall) sales\b",
                  r"\bsales (figures?|numbers?|report|reports|data|trends?|forecast|target|targets|performance|by|per|analysis|team|growth|volume|breakdown)\b",
                  r"\b(kal|aaj|pichle \w+|is \w+) (ki|ka) sales\b")),
    ("warehouse", _re(r"\b(warehouse|godown|distribution cent(er|re)|inventory (levels?|count|turnover|report|value|data)|stock (levels?|count|on hand|report|value|ageing|aging|turnover)|"
                      r"stock[- ]?outs?|overstock(ed)?|dead stock|slow[- ]moving|safety stock|reorder (point|level|quantity)|replenish(ment)?|sell[- ]through|days of (cover|stock)|how much stock do we)\b")),
    ("forecast", _re(r"\b(forecast(s|ing)?|projections?|predict(ion|ions)?)\b[^.?!]{0,25}\b(demand|sales|revenue|stock|inventory|orders|growth|next (month|quarter|year|week))\b",
                     r"\b(demand|sales|revenue|inventory|stock|order) (forecast|forecasts|forecasting|projection|prediction)\b", r"\bdemand planning\b",
                     r"\bwhich products (are )?(likely|going) to (stock ?out|run out|sell out)\b", r"\blikely to stock ?out\b")),
    ("suppliers", _re(r"\b(suppliers?|vendors?|procurement|purchase orders?|supplier (quotes?|onboarding|scorecard)|sourcing|buyer approval|lead times?|moq|incoterms|freight (cost|rates?)|customs duty|hs code)\b")),
    ("internal", _re(r"\b(kpis?|internal (ops|operations|report|reports|data|metrics|numbers)|business (metrics|intelligence|performance|numbers)|company (data|metrics|performance)|admin (panel|console|report))\b")),
    ("other_customers", _re(r"\b(top|best|biggest|all|list of|how many) (customers?|users?|buyers?|accounts?)\b", r"\bcustomers? (who|that|with)\b[^.?!]{0,30}\b(bought|ordered|purchased|spent|returned|complained|signed up|registered)\b",
                            r"\b(customer|user) (list|emails?|phone numbers?|database|data|records?|segments?|lifetime value|ltv|churn)\b", r"\bchurn\b",
                            r"\banother (customer|user)'?s?\b", r"\b(customer|user)s? (emails?|e-mails?)\b", r"\bsigned up (this|last)\b", r"\busers? signed up\b")),
]

# ---------------------------------------------------------------------------------------------------------------- STRONG allow
_WEAK_VOCAB = {"hero", "arena", "mat", "pool", "vest", "bands", "butterfly", "bag", "bags", "swim", "running", "jogging", "marathon", "bicycle", "ss", "sg", "puma", "decathlon", "wilson", "arena", "gym", "yoga", "workout", "fitness"}


def _words(keys) -> re.Pattern[str]:
    return re.compile(r"\b(?:" + "|".join(sorted((re.escape(k) for k in keys), key=len, reverse=True)) + r")\b", re.I)


_VOCAB = _words({*SPORTS, *CATEGORIES, *BRANDS} - _WEAK_VOCAB)
_VOCAB_WEAK = _words(_WEAK_VOCAB)

_SMALLTALK = re.compile(r"^\W*(hi+|hello|hey|namaste|good (morning|afternoon|evening)|thanks|thank you|thx|bye|goodbye|ok|okay|yes|yep|yeah|no|nope|sure|cancel)\W*$|"
                        r"^\W*(hi+|hello|hey|namaste)\b[^.?!]{0,25}\b(what can you do|can you help|how can you help|help me)\b[^.?!]{0,15}\W*$|"
                        r"^\W*(what can you do|how can you help( me)?|can you help( me)?|help( me)?)\W*$", re.I)
_COMMERCE = _re(r"\b(orders?|order status|cart|basket|checkout|refunds?|returns? policy|refund policy|shipping|shipment|delivery|delivered|deliver|tracking|parcel|package|courier|in stock|out of stock|warranty|guarantee|"
                r"coupons?|promo ?codes?|discounts?|wishlist|dsports|my account|invoice|exchange|replacement|complaint|ORD-\d+|CASE-\d+)\b",
                r"\b(paisa wapas|paise wapas|joote|jute|gend|ballebaaz|dukaan|kimat|daam|sasta|saste|mehenga|kharid\w*|khareed\w*|bhugtan)\b",
                r"\bkaun sa (bat|joota|ball)\b", r"\b(buy|purchase|bought) (a|an|the|some|new)\b")
_COMMERCE_WEAK = _re(r"\b(compare|comparison|recommend|suggest|price|prices|cost|buy|purchase|sale|offers?|size|sizes|weight|reviews?|ratings?|stock|kit|gear|equipment|products?|brand|store|available|cheap|cheaper|expensive)\b")
# Booking words that mean a booking wherever they appear, and words that also name a kind of shoe or boot ("court shoes", "firm ground boots"):
# the latter only mean a booking when no product noun is in the message.
_BOOKING = _re(r"\b(venues?|bookings?|reservations?|reserve|reserved)\b", r"\bbook (a|an|the|my|me|this|that|it|one|karna|karo)\b", r"\bnets? (session|slot|practice)\b",
               r"\bslots?\b", r"\bindoor nets\b", r"\b(court|ground|slot|venue|turf) (book|khali|available|milega|chahiye)\b", r"\bbook karn[ao]\b")
_BOOKING_PLACE = _re(r"\b(courts?|grounds?|turfs?)\b")
_PRODUCT_NOUN = _words(set(CATEGORIES))
_CRICKET_OWN = _re(r"\b(my|mera|meri|mere|our)\b[^.?!]{0,12}\b(matches|match|scorecard|stats|statistics|team|teams|innings|runs|wickets|batting average|batting|bowling|strike rate|career|tournaments?|league|games?|score)\b",
                   r"\bscorecard\b", r"\b(show|list|see) (my )?(matches|games)\b", r"\bscore (a|the|my) match\b", r"\bscoring (a|the|my) match\b")
_CRICKET_RULES = _re(r"\b(lbw|leg before|no[- ]balls?|wickets?|wicket ?keeper|batsman|batsmen|batters?|bowlers?|umpires?|scorer|innings|powerplays?|dls|duckworth|net run rate|nrr|strike rate|economy rate|yorkers?|bouncers?|googly|doosra|cover drive|super over|free hit|follow[- ]on|run[- ]?outs?|leg byes?|maiden overs?|hat-?trick|all-?rounder)\b",
                     r"\b\d+ overs?\b", r"\bovers? (in|per)\b", r"\bhow many (runs|overs|players|wickets)\b", r"\bhow (is|are) [^.?!]{0,25}\b(scored|counted|calculated)\b",
                     r"\b(rules|laws) of (the )?(game|cricket)\b", r"\bhow to (bat|bowl|keep wicket|field|play (a|the) (cover|pull|sweep|drive|shot|spin|pace))\b")


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).strip()


def _deny_rule(text: str) -> str | None:
    own = _OWN.search(text) is not None
    for name, pattern, own_exempt, safe in _DENY:
        if own_exempt and own:
            continue
        if safe is not None and safe.search(text):
            continue
        if pattern.search(text):
            return name
    return None


def _business_rule(text: str) -> str | None:
    return next((name for name, pattern in _BUSINESS if pattern.search(text)), None)


def _allow_topic(text: str) -> tuple[Topic, str] | None:
    if _SMALLTALK.match(text):
        return Topic.SHOP, "smalltalk"
    product = _PRODUCT_NOUN.search(text) is not None        # a bat, shoes, a jersey...: the message is about gear, not about a booking or a match
    if _BOOKING.search(text) or (not product and _BOOKING_PLACE.search(text)):
        return Topic.BOOKING, "booking"
    if not product and _CRICKET_OWN.search(text):
        return Topic.CRICKET, "cricket_own"
    if not product and _CRICKET_RULES.search(text):
        return Topic.CRICKET, "cricket_rules"
    if _VOCAB.search(text):
        return Topic.SHOP, "shop_vocab"
    if _COMMERCE.search(text):
        return Topic.SHOP, "commerce"
    if _CRICKET_RULES.search(text) or _CRICKET_OWN.search(text):
        return Topic.SHOP, "cricket_words_with_product"
    if _VOCAB_WEAK.search(text) and (_COMMERCE.search(text) or _COMMERCE_WEAK.search(text)):
        return Topic.SHOP, "weak_vocab_with_commerce"
    if _tier0_account_signal(text):
        return Topic.SHOP, "tier0_account"
    return None


def _tier0_account_signal(text: str) -> bool:
    """The Tier 0 rules already recognise orders, payments, refunds, returns, complaints and support cases precisely ("I was charged twice", "my return
    request was rejected"). That recognition is reused as allow evidence, but only for those families: the knowledge/definition rules ("how to use ...") are
    deliberately NOT evidence, because they match plenty of off-topic questions. Deny and business rules have already been applied before this is reached."""
    from app.router import tier0
    from app.router.intents import ORDERS, SUPPORT, Intent
    route = tier0.classify(text, None, authenticated=True, known_products=frozenset())
    return route is not None and route.intent in (ORDERS | SUPPORT | {Intent.RECOMMEND, Intent.KIT_BUILD})


_SPLIT = re.compile(r"(?<=[.?!;])\s+|\n+|,\s+|\s+(?:and also|and then|also|and|aur|plus|then|but|as well as)\s+", re.I)


_CONNECTOR_ONLY = re.compile(r"^\W*(also|and|then|but|plus|aur|and also|and then)\W*$", re.I)


def _clause_topic(text: str) -> tuple[Topic, str] | None:
    """What a remaining clause is, for a mixed message: only a real signal counts (BUSINESS or an allowed topic)."""
    if (rule := _business_rule(text)) is not None:
        return Topic.BUSINESS, rule
    return _allow_topic(text)


def decide(message: str, *, has_context: bool = False) -> Verdict:
    text = _norm(message)
    # A deny rule is judged per clause (the "this is about my own order" exemption must not cover a different clause), then on the whole text for
    # patterns that span a comma. Looking at clauses never rewrites the message: only a deny hit leads to the split below.
    deny = next((d for c in _SPLIT.split(text) if c and c.strip() and (d := _deny_rule(c)) is not None), None) or _deny_rule(text)
    if deny is not None:
        keep = [c.strip() for c in _SPLIT.split(text) if c and c.strip() and not _CONNECTOR_ONLY.match(c) and _deny_rule(c) is None]
        remaining = " ".join(keep).strip(" ,;.&")
        if remaining and len(remaining.split()) >= 3 and (found := _clause_topic(remaining)) is not None:
            return Verdict(found[0], f"mixed:{deny}", forward=remaining)
        return Verdict(Topic.OFF_TOPIC, deny)
    if (rule := _business_rule(text)) is not None:
        return Verdict(Topic.BUSINESS, rule)
    if (found := _allow_topic(text)) is not None:
        return Verdict(found[0], found[1])
    if has_context:
        return Verdict(None, "context", context=True)
    return Verdict(None, "unsure")
