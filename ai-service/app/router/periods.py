"""Deterministic parsing of date phrases ("last month", "in September", "last 3 weeks") into a UTC date range.

Dates are calendar days in UTC, the same clock the order timestamps use.
"""
from __future__ import annotations

import calendar
import datetime as dt
import re
from dataclasses import dataclass

_MONTHS = {m.lower(): i for i, m in enumerate(calendar.month_name) if m}
_MONTHS.update({m.lower(): i for i, m in enumerate(calendar.month_abbr) if m})


@dataclass(frozen=True)
class Period:
    since: dt.date
    until: dt.date
    label: str


def _month_range(year: int, month: int) -> tuple[dt.date, dt.date]:
    return dt.date(year, month, 1), dt.date(year, month, calendar.monthrange(year, month)[1])


def parse_period(text: str, today: dt.date) -> Period | None:
    t = text.lower()
    if re.search(r"\btoday\b", t):
        return Period(today, today, "today")
    if re.search(r"\byesterday\b", t):
        d = today - dt.timedelta(days=1)
        return Period(d, d, "yesterday")
    if re.search(r"\blast month\b|\bprevious month\b|\bpast month\b", t):
        first_this = today.replace(day=1)
        last_prev = first_this - dt.timedelta(days=1)
        s, e = _month_range(last_prev.year, last_prev.month)
        return Period(s, e, f"last month ({calendar.month_name[last_prev.month]} {last_prev.year})")
    if re.search(r"\bthis month\b", t):
        s, e = _month_range(today.year, today.month)
        return Period(s, min(e, today), "this month")
    if re.search(r"\blast week\b|\bprevious week\b", t):
        monday = today - dt.timedelta(days=today.weekday())
        return Period(monday - dt.timedelta(days=7), monday - dt.timedelta(days=1), "last week")
    if re.search(r"\bthis week\b", t):
        return Period(today - dt.timedelta(days=today.weekday()), today, "this week")
    if re.search(r"\blast year\b", t):
        return Period(dt.date(today.year - 1, 1, 1), dt.date(today.year - 1, 12, 31), "last year")
    m = re.search(r"\b(?:last|past|previous)\s+(\d{1,3})\s+(day|week|month)s?\b", t)
    if m:
        n, unit = int(m.group(1)), m.group(2)
        days = n * {"day": 1, "week": 7, "month": 30}[unit]
        return Period(today - dt.timedelta(days=days), today, f"last {n} {unit}{'s' if n != 1 else ''}")
    m = re.search(r"\b(?:in|during|from|of)\s+(" + "|".join(sorted(_MONTHS, key=len, reverse=True)) + r")\b(?:\s+(\d{4}))?", t)
    if m:
        month = _MONTHS[m.group(1)]
        year = int(m.group(2)) if m.group(2) else (today.year if month <= today.month else today.year - 1)
        s, e = _month_range(year, month)
        return Period(s, e, f"{calendar.month_name[month]} {year}")
    return None
