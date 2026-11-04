"""Deterministic entity extraction. Used by Tier 0 directly, and by Tier 1 as a verifier: a number the model "extracted" that
does not appear in the customer's words is discarded, so a budget can never be hallucinated."""
from __future__ import annotations

import re
from typing import Any

_NUM = r"(?:₹|rs\.?|inr)?\s*(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakhs?|lacs?|rupees?|rs\.?|inr)?"
_UNDER = re.compile(r"\b(?:under|below|less than|within|upto|up to|max(?:imum)?|at most|not more than|no more than|cheaper than|budget(?: of| is)?|around|about)\s*" + _NUM, re.I)
_OVER = re.compile(r"\b(?:over|above|more than|at least|min(?:imum)?|starting (?:from|at))\s*" + _NUM, re.I)
_BETWEEN = re.compile(r"\bbetween\s*" + _NUM + r"\s*(?:and|to|-)\s*" + _NUM, re.I)
_SYMBOL = re.compile(r"(?:₹|rs\.?\s|inr\s)\s*(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakhs?|lacs?)?", re.I)
_ORDER_NUMBER = re.compile(r"\bORD-\d{8}-\d{6}\b", re.I)
_ORDER_DIGITS = re.compile(r"\border\s*(?:number|no\.?|#|id)?\s*[:#]?\s*(\d{1,6})\b", re.I)
_LATEST = re.compile(r"\b(last|latest|most recent|recent|previous|newest)\s+(order|purchase)|\bmy (last|latest|recent) (order|purchase)\b|\bmy order\b|\bmy (package|parcel|shipment)\b", re.I)
_ORDINALS = {"first": 1, "1st": 1, "one": 1, "second": 2, "2nd": 2, "two": 2, "third": 3, "3rd": 3, "three": 3, "fourth": 4, "4th": 4,
             "fifth": 5, "5th": 5, "last": -1}

SPORTS = {"table tennis": "Table Tennis", "ping pong": "Table Tennis", "cricket": "Cricket", "football": "Football", "soccer": "Football", "badminton": "Badminton", "tennis": "Tennis",
          "basketball": "Basketball", "volleyball": "Volleyball",
          "running": "Running", "jogging": "Running", "marathon": "Running", "gym": "Fitness", "fitness": "Fitness", "workout": "Fitness", "yoga": "Fitness",
          "swimming": "Swimming", "swim": "Swimming", "pool": "Swimming", "cycling": "Cycling", "bicycle": "Cycling", "cyclist": "Cycling"}
# word -> catalogue category name
CATEGORIES = {"bat": "Bat", "bats": "Bat", "racket": "Bat", "rackets": "Bat", "racquet": "Bat", "racquets": "Bat",
              "ball": "Ball", "balls": "Ball", "shuttlecock": "Ball", "shuttlecocks": "Ball",
              "shoe": "Shoes", "shoes": "Shoes", "boots": "Shoes", "boot": "Shoes", "footwear": "Shoes", "spikes": "Shoes", "sneakers": "Shoes",
              "gloves": "Gloves", "glove": "Gloves", "pads": "Gloves", "helmet": "Helmet", "helmets": "Helmet",
              "jersey": "Jersey", "jerseys": "Jersey", "shorts": "Jersey", "vest": "Jersey",
              "bag": "Kit Bag", "bags": "Kit Bag", "kitbag": "Kit Bag", "backpack": "Kit Bag",
              "dumbbell": "Equipment", "dumbbells": "Equipment", "kettlebell": "Equipment", "kettlebells": "Equipment", "bands": "Equipment", "mat": "Equipment",
              "mats": "Equipment", "rope": "Equipment", "roller": "Equipment", "plates": "Equipment", "cones": "Equipment",
              "kickboard": "Equipment", "fins": "Equipment", "buoy": "Equipment", "equipment": "Equipment", "watch": "Wearables", "watches": "Wearables",
              "tracker": "Wearables", "bike": "Bike", "bikes": "Bike", "bicycles": "Bike", "paddle": "Bat", "paddles": "Bat", "trainers": "Shoes",
              "goggles": "Accessories", "bottle": "Accessories", "bottles": "Accessories", "belt": "Accessories", "net": "Accessories", "nets": "Accessories",
              "guard": "Accessories", "guards": "Accessories", "sleeves": "Accessories", "sleeve": "Accessories", "glasses": "Accessories", "case": "Kit Bag",
              "swimsuit": "Jersey", "swimwear": "Jersey", "tshirt": "Jersey", "t-shirt": "Jersey", "tee": "Jersey",
              "accessories": "Accessories", "accessory": "Accessories", "grip": "Accessories", "cap": "Accessories", "socks": "Accessories",
              "wristband": "Accessories", "shin": "Accessories"}
SKILLS = [("beginner", r"\b(beginner|beginners|novice|starter|just (started|starting)|new to|learning|first bat|first time)\b"),
          ("intermediate", r"\b(intermediate|club (player|cricket)|improving)\b"),
          ("advanced", r"\b(advanced|professional|pro level|expert|elite|serious player)\b")]
AGE = [("junior", r"\b(junior|kid|kids|child|children|boy|boys|girl|girls|youth|teen|teenager|son|daughter|nephew|niece)\b"), ("adult", r"\b(adult|adults|grown[- ]?up|men'?s|women'?s)\b")]
WEIGHT = [("light", r"\b(light|lighter|lightweight|light-weight|light weight|lightest)\b"), ("heavy", r"\b(heavy|heavier|heaviest)\b")]
BRANDS = {"mrf": "MRF", "ss": "SS", "sg": "SG", "kookaburra": "Kookaburra", "gray nicolls": "Gray Nicolls", "gray-nicolls": "Gray Nicolls",
          "dsc": "DSC", "spartan": "Spartan", "nike": "Nike", "adidas": "Adidas", "puma": "Puma", "nivia": "Nivia", "decathlon": "Decathlon", "yonex": "Yonex",
          "li-ning": "Li-Ning", "li ning": "Li-Ning", "wilson": "Wilson", "babolat": "Babolat", "asics": "Asics", "reebok": "Reebok", "under armour": "Under Armour",
          "speedo": "Speedo", "arena": "Arena", "hero": "Hero", "btwin": "Btwin", "stiga": "Stiga", "butterfly": "Butterfly", "cosco": "Cosco", "garmin": "Garmin",
          "mizuno": "Mizuno"}


def _amount(number: str, unit: str | None) -> float:
    value = float(number.replace(",", ""))
    unit = (unit or "").lower()
    if unit in ("k", "thousand"):
        value *= 1000
    elif unit.startswith(("lakh", "lac")):
        value *= 100_000
    return value


def extract_budget(text: str) -> dict[str, float]:
    out: dict[str, float] = {}
    if m := _BETWEEN.search(text):
        lo, hi = _amount(m.group(1), m.group(2)), _amount(m.group(3), m.group(4))
        out["min_price"], out["max_price"] = min(lo, hi), max(lo, hi)
        return out
    if m := _UNDER.search(text):
        out["max_price"] = _amount(m.group(1), m.group(2))
    if m := _OVER.search(text):
        out["min_price"] = _amount(m.group(1), m.group(2))
    if not out and (m := _SYMBOL.search(text)):
        out["max_price"] = _amount(m.group(1), m.group(2))  # "a bat for ₹8,000" reads as a ceiling
    return out


def numbers_in(text: str) -> set[float]:
    return {_amount(n, u) for n, u in re.findall(r"(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakhs?|lacs?)?", text, re.I)} | \
           {float(n.replace(",", "")) for n in re.findall(r"\d[\d,]*(?:\.\d+)?", text)}


def extract_order_ref(text: str) -> dict[str, Any]:
    if m := _ORDER_NUMBER.search(text):
        return {"order_number": m.group(0).upper()}
    if m := _ORDER_DIGITS.search(text):
        return {"order_number": m.group(1)}
    if _LATEST.search(text):
        return {"latest": True}
    return {}


def extract_ordinals(text: str) -> list[int]:
    found: list[int] = []
    if m := re.search(r"\b(?:first|top|these|those|the)\s+(two|three|four|five|2|3|4|5)\b", text, re.I):
        count = {"two": 2, "three": 3, "four": 4, "five": 5}.get(m.group(1).lower()) or int(m.group(1))
        return list(range(1, count + 1))
    if re.search(r"\bboth\b", text, re.I):
        return [1, 2]
    for word, n in _ORDINALS.items():
        if re.search(rf"\b{re.escape(word)}\b(?:\s+(?:one|option|product|bat|pair|item))?", text, re.I) and word not in ("one", "two", "three", "last"):
            found.append(n)
    for m in re.finditer(r"(?:#|number\s+|option\s+|no\.?\s*)(\d)\b", text, re.I):
        found.append(int(m.group(1)))
    for word in ("one", "two", "three"):
        if re.search(rf"\b(?:the\s+)?{word}\b\s+(?:one|option)\b|\boption\s+{word}\b", text, re.I):
            found.append(_ORDINALS[word])
    return list(dict.fromkeys(found))


def extract_shopping_slots(text: str) -> dict[str, Any]:
    low = text.lower()
    slots: dict[str, Any] = {}
    for word, sport in SPORTS.items():
        if re.search(rf"\b{word}\b", low):
            slots["sport"] = sport
            break
    # Longest-first so "kit bag" is not read as "bag"-only plus noise; first mention wins.
    cat_pos = [(m.start(), CATEGORIES[m.group(0)]) for m in re.finditer(r"\b(" + "|".join(sorted(CATEGORIES, key=len, reverse=True)) + r")\b", low)]
    if len({c for _, c in cat_pos}) > 1:   # "bike helmet" is a helmet; "bike" alone is a bike
        cat_pos = [(pos, c) for pos, c in cat_pos if c != "Bike"] or cat_pos
    if cat_pos:
        slots["category"] = sorted(cat_pos)[0][1]
    for word, brand in BRANDS.items():
        if re.search(rf"\b{re.escape(word)}\b", low):
            slots["brand"] = brand
            break
    for level, pattern in SKILLS:
        if re.search(pattern, low):
            slots["skill_level"] = level
            break
    for group, pattern in AGE:
        if re.search(pattern, low):
            slots["age_group"] = group
            break
    for pref, pattern in WEIGHT:
        if re.search(pattern, low):
            slots["weight_pref"] = pref
            break
    slots.update(extract_budget(text))
    return slots
