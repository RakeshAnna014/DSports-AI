"""The planning calendar: weeks, months, quarters and the financial year. Pure functions, no I/O.

A WEEK is identified by its first day (`week_start`), which is a date, never a timestamp, in the business timezone. The first weekday is a setting (0 = Monday).
A week belongs to the month, quarter and financial year that contain its FIRST day, so every week has exactly one of each and rollups add up.
The FINANCIAL YEAR starts on the first day of `fy_start_month` (April by default) and is named by the calendar year it starts in: FY2026 runs 2026-04-01 to 2027-03-31
and is labelled "FY2026-27". A business on the calendar year sets the start month to 1 (label "FY2026").
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class Calendar:
    timezone_name: str = "Asia/Kolkata"
    week_start_day: int = 0           # 0 = Monday ... 6 = Sunday
    fy_start_month: int = 4

    def __post_init__(self) -> None:
        if not 0 <= self.week_start_day <= 6:
            raise ValueError("week_start_day must be 0 (Monday) to 6 (Sunday)")
        if not 1 <= self.fy_start_month <= 12:
            raise ValueError("fy_start_month must be 1 to 12")
        ZoneInfo(self.timezone_name)    # raises for an unknown zone

    # ------------------------------------------------------------------ time zone
    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone_name)

    def local_date(self, moment: datetime) -> date:
        """The business date of an instant. A naive datetime is treated as UTC (the database stores timestamptz)."""
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        return moment.astimezone(self.tz).date()

    def today(self, now: datetime | None = None) -> date:
        return self.local_date(now or datetime.now(timezone.utc))

    # ------------------------------------------------------------------ weeks
    def week_start(self, day: date) -> date:
        return day - timedelta(days=(day.weekday() - self.week_start_day) % 7)

    def week_end(self, week_start: date) -> date:
        return week_start + timedelta(days=6)

    def week_of(self, moment: datetime) -> date:
        return self.week_start(self.local_date(moment))

    def weeks(self, first: date, last: date) -> list[date]:
        """Every week start from the week of `first` to the week of `last`, inclusive."""
        out, w, end = [], self.week_start(first), self.week_start(last)
        while w <= end:
            out.append(w)
            w += timedelta(days=7)
        return out

    def add_weeks(self, week_start: date, n: int) -> date:
        return week_start + timedelta(days=7 * n)

    def is_week_start(self, day: date) -> bool:
        return self.week_start(day) == day

    def last_closed_week(self, data_through: date) -> date:
        """The latest week whose last day is on or before `data_through` (the last day for which the source is complete)."""
        w = self.week_start(data_through)
        return w if self.week_end(w) <= data_through else w - timedelta(days=7)

    # ------------------------------------------------------------------ financial calendar
    def fy_year(self, day: date) -> int:
        """The calendar year in which the financial year containing `day` starts."""
        return day.year if day.month >= self.fy_start_month else day.year - 1

    def fy_label(self, fy: int) -> str:
        return f"FY{fy}" if self.fy_start_month == 1 else f"FY{fy}-{str(fy + 1)[-2:]}"

    def fy_bounds(self, fy: int) -> tuple[date, date]:
        start = date(fy, self.fy_start_month, 1)
        nxt = date(fy + 1, self.fy_start_month, 1)
        return start, nxt - timedelta(days=1)

    def fy_quarter(self, day: date) -> int:
        return ((day.month - self.fy_start_month) % 12) // 3 + 1

    def fy_month(self, day: date) -> int:
        """1..12 within the financial year."""
        return (day.month - self.fy_start_month) % 12 + 1

    def describe_week(self, week_start: date) -> dict:
        fy = self.fy_year(week_start)
        return {"week_start": week_start.isoformat(), "week_end": self.week_end(week_start).isoformat(), "month": week_start.strftime("%Y-%m"), "fy": fy, "fy_label": self.fy_label(fy),
                "fy_quarter": self.fy_quarter(week_start), "fy_month": self.fy_month(week_start),
                "fy_week": (week_start - self.first_week_of_fy(fy)).days // 7 + 1}

    def first_week_of_fy(self, fy: int) -> date:
        """The first week that STARTS in the financial year (a week that straddles the year boundary belongs to the earlier year)."""
        start = self.fy_bounds(fy)[0]
        first = self.week_start(start)
        return first if first >= start else first + timedelta(days=7)

    def period_weeks(self, kind: str, fy: int, number: int | None = None) -> list[date]:
        """The week starts that belong to a financial year ('FY'), a quarter ('Q', number 1..4) or a financial month ('M', number 1..12)."""
        start, end = self.fy_bounds(fy)
        out = []
        for w in self.weeks(start, end):
            if self.fy_year(w) != fy:
                continue
            if kind == "FY" or (kind == "Q" and self.fy_quarter(w) == number) or (kind == "M" and self.fy_month(w) == number):
                out.append(w)
        if kind not in ("FY", "Q", "M"):
            raise ValueError("kind must be FY, Q or M")
        return out
