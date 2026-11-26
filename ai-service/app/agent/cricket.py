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


_COMPARISON = re.compile(r"\b(difference|different|differences|versus|vs|compare|comparison|or)\b", re.I)


def find(message: str, glossary: list[Entry]) -> list[Entry]:
    """The entry the message is about: the one whose term is mentioned first (a longer term wins a tie, and a match inside another match is dropped).
    Only a comparison question ("the difference between X and Y") may return a second entry."""
    low = re.sub(r"\s+", " ", " " + re.sub(r"[^a-z0-9+ ]+", " ", message.lower().replace("-", " ")) + " ")
    spans: list[tuple[int, int, Entry]] = []
    for entry in glossary:
        for term in entry.terms:
            needle = " " + re.sub(r"\s+", " ", term.replace("-", " ")) + " "
            at = low.find(needle)
            if at >= 0:
                spans.append((at, at + len(needle), entry))
    spans.sort(key=lambda s: (s[0], -(s[1] - s[0])))
    chosen: list[tuple[int, int, Entry]] = []
    for start, end, entry in spans:
        if any(start < c_end and end > c_start for c_start, c_end, _ in chosen) or any(entry is c for _, _, c in chosen):
            continue
        chosen.append((start, end, entry))
    limit = 2 if _COMPARISON.search(message) else 1
    return [entry for _, _, entry in chosen[:limit]]


def reply(message: str, glossary: list[Entry] | None = None) -> Template:
    glossary = load_glossary() if glossary is None else glossary
    asks_about_me = _OWN_DATA.search(message) is not None
    entries = [] if asks_about_me else find(message, glossary)
    if entries:
        text = "\n\n".join(f"{e.title}: {e.answer}" for e in entries)
        return Template("glossary", text + "\n\n(From the DSports cricket glossary, a summary of the standard laws. Your league's own rules can differ.)",
                        (Chip("Find a cricket bat", message="Show me cricket bats"), Chip("My matches", href="/matches")))
    if asks_about_me:
        return templates.CRICKET_POINTER
    if _COACHING.search(message):
        return templates.COACHING_SOON
    return GLOSSARY_MISS
