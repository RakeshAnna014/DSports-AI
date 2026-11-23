"""Replies to cricket questions that are not shopping: always fixed text, never a model.

* A rules or definition question ("what is LBW") is answered from the reviewed glossary (``data/cricket_glossary.json``), word for word.
* A question about the customer's own matches, scores or stats gets a fixed pointer to the pages that show them (the assistant has no match tools yet).
* A coaching or technique question is answered from the glossary when an entry matches, otherwise "coaching tips are coming soon".
* Anything else cricket-shaped that the glossary does not cover says so plainly. Nothing is invented and no model is asked.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from app.guard import topic_templates as templates
from app.guard.topic_templates import Chip, Template

GLOSSARY_PATH = Path(__file__).with_name("data") / "cricket_glossary.json"

_OWN_DATA = re.compile(r"\b(my|mera|meri|mere|our)\b[^.?!]{0,14}\b(matches|match|scorecard|stats|statistics|team|teams|innings|runs|wickets|average|strike rate|career|tournaments?|league|games?|score|scores)\b|"
                       r"\bscorecard\b|\b(show|list|see) (my )?(matches|games)\b|\bscor(e|ing) (a|the|my) match\b|\bstill live\b", re.I)
_COACHING = re.compile(r"\b(tips?|improve|technique|coach(ing)?|drills?|practice|how (do|can|should) i|how to|stance|grip|footwork|backlift|follow[- ]through|bowling action|training)\b", re.I)

GLOSSARY_MISS = Template("glossary_miss",
                         "I don't have a reviewed answer for that cricket question yet. I can explain the terms and rules in my cricket glossary, such as LBW, a free hit or net run rate, and help you find gear.",
                         (Chip("What is LBW?", message="What is LBW?"), Chip("Find a cricket bat", message="Show me cricket bats"), Chip("My matches", href="/matches")))


@dataclass(frozen=True)
class Entry:
    id: str
    title: str
    terms: tuple[str, ...]
    answer: str


def load_glossary(path: Path = GLOSSARY_PATH) -> list[Entry]:
    if not path.exists():
        return []
    rows = json.loads(path.read_text(encoding="utf-8"))
    return [Entry(r["id"], r["title"], tuple(t.lower() for t in r["terms"]), r["answer"]) for r in rows]


def find(message: str, glossary: list[Entry]) -> list[Entry]:
    """Entries whose term appears as whole words in the message, in the order they are mentioned (at most two: "the difference between X and Y")."""
    low = " " + re.sub(r"[^a-z0-9+ ]+", " ", message.lower().replace("-", " ")) + " "
    low = re.sub(r"\s+", " ", low)
    hits: list[tuple[int, int, Entry]] = []
    for entry in glossary:
        for term in entry.terms:
            needle = " " + re.sub(r"\s+", " ", term.replace("-", " ")) + " "
            at = low.find(needle)
            if at >= 0:
                hits.append((at, -len(term), entry))
                break
    hits.sort(key=lambda h: (h[0], h[1]))
    seen: list[Entry] = []
    for _, _, entry in hits:
        if entry not in seen:
            seen.append(entry)
    # "leg bye" contains "bye": when one matched term is inside another matched entry's term, keep only the longer one
    keep = [e for e in seen if not any(e is not o and any(t in ot for t in e.terms for ot in o.terms) and len(max(o.terms, key=len)) > len(max(e.terms, key=len)) for o in seen)] or seen
    return keep[:2]


def reply(message: str, glossary: list[Entry] | None = None) -> Template:
    glossary = load_glossary() if glossary is None else glossary
    asks_about_me = _OWN_DATA.search(message) is not None
    entries = [] if asks_about_me else find(message, glossary)
    if entries:
        text = "\n\n".join(f"{e.title}: {e.answer}" for e in entries)
        return Template("glossary", text + "\n\n(From the DSports cricket glossary.)",
                        (Chip("Find a cricket bat", message="Show me cricket bats"), Chip("My matches", href="/matches")))
    if asks_about_me:
        return templates.CRICKET_POINTER
    if _COACHING.search(message):
        return templates.COACHING_SOON
    return GLOSSARY_MISS
