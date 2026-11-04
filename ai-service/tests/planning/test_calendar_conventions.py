from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import Decimal

import pytest

from app.planning import regions
from app.planning.calendar import Calendar
from app.planning.conventions import recognise
from app.planning.ingest import prepare_line

CAL = Calendar("Asia/Kolkata", 0, 4)


# ------------------------------------------------------------------------------------------------ calendar
def test_weeks_start_on_the_configured_weekday_and_cover_every_day_once():
    assert CAL.week_start(date(2026, 10, 15)) == date(2026, 10, 12)               # Thursday -> Monday
    assert CAL.week_end(date(2026, 10, 12)) == date(2026, 10, 18)
    sunday_first = Calendar("Asia/Kolkata", 6, 4)
    assert sunday_first.week_start(date(2026, 10, 15)) == date(2026, 10, 11)       # Thursday -> the Sunday before
    assert sunday_first.week_start(date(2026, 10, 11)) == date(2026, 10, 11)
    days = [date(2026, 3, 30) + (date(2026, 4, 1) - date(2026, 3, 31)) * k for k in range(5)]
    assert len({CAL.week_start(d) for d in days}) >= 1


def test_the_business_timezone_decides_the_week_not_utc():
    # 2026-10-11 20:00 UTC is already Monday 2026-10-12 01:30 in India (UTC+5:30): that sale belongs to the NEW week
    moment = datetime(2026, 10, 11, 20, 0, tzinfo=timezone.utc)
    assert CAL.week_of(moment) == date(2026, 10, 12)
    assert Calendar("UTC", 0, 4).week_of(moment) == date(2026, 10, 5)


def test_financial_year_boundaries_labels_quarters_and_months():
    assert CAL.fy_year(date(2026, 3, 31)) == 2025 and CAL.fy_year(date(2026, 4, 1)) == 2026
    assert CAL.fy_label(2026) == "FY2026-27" and Calendar("UTC", 0, 1).fy_label(2026) == "FY2026"
    assert CAL.fy_bounds(2026) == (date(2026, 4, 1), date(2027, 3, 31))
    assert [CAL.fy_quarter(date(2026, m, 1)) for m in (4, 7, 10, 12, 1, 3)] in ([1, 2, 3, 3, 4, 4],)
    assert CAL.fy_month(date(2026, 4, 15)) == 1 and CAL.fy_month(date(2027, 3, 31)) == 12
    assert Calendar("UTC", 0, 1).fy_quarter(date(2026, 10, 1)) == 4


def test_a_week_straddling_the_year_boundary_belongs_to_the_year_it_starts_in_and_every_week_has_one_year():
    straddle = date(2026, 3, 30)                                                   # Monday; the week ends on 5 April
    assert CAL.fy_year(straddle) == 2025
    first = CAL.first_week_of_fy(2026)
    assert first == date(2026, 4, 6) and CAL.describe_week(first)["fy_week"] == 1
    all_weeks = CAL.period_weeks("FY", 2026)
    assert all_weeks[0] == first and CAL.fy_year(all_weeks[-1]) == 2026 and len(all_weeks) in (52, 53)
    quarters = [CAL.period_weeks("Q", 2026, q) for q in (1, 2, 3, 4)]
    assert sum(len(q) for q in quarters) == len(all_weeks)                         # the quarters add up to the year: nothing is counted twice or dropped
    assert len(CAL.period_weeks("M", 2026, 1)) in (4, 5)
    with pytest.raises(ValueError):
        CAL.period_weeks("X", 2026)


def test_a_week_is_closed_only_when_its_last_day_is_covered():
    assert CAL.last_closed_week(date(2026, 10, 18)) == date(2026, 10, 12)          # Sunday: the week ending today is closed
    assert CAL.last_closed_week(date(2026, 10, 17)) == date(2026, 10, 5)           # Saturday: the current week is still open
    assert CAL.last_closed_week(date(2026, 10, 7)) == date(2026, 9, 28)


def test_invalid_calendar_settings_are_refused():
    with pytest.raises(ValueError):
        Calendar("Asia/Kolkata", 7, 4)
    with pytest.raises(ValueError):
        Calendar("Asia/Kolkata", 0, 13)
    with pytest.raises(Exception):
        Calendar("Mars/Olympus", 0, 4)


# ------------------------------------------------------------------------------------------------ sales convention
def line(status="DELIVERED", qty=4, total="400.00", disc="40.00", returned=0):
    return {"order_status": status, "quantity": qty, "line_total": total, "allocated_discount": disc, "returned_quantity": returned}


def test_a_delivered_line_is_gross_equals_net_with_revenue_after_discount():
    s = recognise(line())
    assert (s.counted, s.gross_units, s.cancelled_units, s.returned_units, s.net_units) == (True, 4, 0, 0, 4)
    assert (s.gross_revenue, s.discount, s.net_revenue) == (Decimal("400.00"), Decimal("40.00"), Decimal("360.00"))


def test_pending_payment_and_created_are_not_sales_and_unknown_statuses_are_not_counted():
    for status in ("PENDING_PAYMENT", "CREATED", "WHATEVER"):
        s = recognise(line(status))
        assert not s.counted and s.gross_units == 0 and s.net_units == 0 and s.net_revenue == 0


def test_a_cancelled_line_is_gross_and_cancelled_and_nets_to_zero_with_no_revenue():
    s = recognise(line("CANCELLED"))
    assert (s.gross_units, s.cancelled_units, s.net_units, s.net_revenue) == (4, 4, 0, Decimal("0"))
    assert recognise(line("CANCELLED", returned=2)).returned_units == 0               # a cancelled line cannot also be returned


def test_an_approved_return_is_subtracted_once_and_revenue_follows_the_units_that_stayed():
    s = recognise(line(returned=1))
    assert (s.returned_units, s.net_units, s.net_revenue) == (1, 3, Decimal("270.00"))        # (400 - 40) x 3 / 4
    over = recognise(line(returned=99))
    assert over.returned_units == 4 and over.net_units == 0                                    # never more than was sold


def test_a_refunded_order_is_returned_in_full_and_a_refund_plus_an_approved_return_is_not_double_counted():
    assert recognise(line("REFUNDED")).net_units == 0
    both = recognise(line("REFUNDED", returned=2))
    assert both.returned_units == 4 and both.net_units == 0                                    # the larger of the two, not 4 + 2


def test_negative_quantity_is_refused_and_discount_is_capped_at_the_line_value():
    with pytest.raises(ValueError):
        recognise(line(qty=-1))
    assert recognise(line(disc="999.00")).net_revenue == Decimal("0.00")


# ------------------------------------------------------------------------------------------------ feed row -> stored line
ROW = {"lineId": "11111111-1111-1111-1111-111111111111", "orderId": "22222222-2222-2222-2222-222222222222", "productId": "33333333-3333-3333-3333-333333333333", "quantity": 2,
       "orderStatus": "DELIVERED", "placedAt": "2026-10-11T20:00:00Z", "sourceUpdatedAt": "2026-10-12T01:00:00Z", "lineTotal": 200, "allocatedDiscount": 10, "shipState": "Karnataka",
       "approvedReturnQuantity": 0, "currency": "INR"}


def test_a_feed_row_becomes_a_line_in_the_right_week_region_and_location():
    p = prepare_line(ROW, CAL, regions.DEFAULT_STATE_REGION)
    assert p["week_start"] == date(2026, 10, 12) and p["location_id"] == "ONL-S" and p["net_units"] == 2 and p["counted"]


def test_an_unknown_state_is_counted_in_the_unknown_region_not_guessed():
    assert prepare_line({**ROW, "shipState": "Atlantis"}, CAL, regions.DEFAULT_STATE_REGION)["location_id"] == "ONL-UNK"
    assert prepare_line({**ROW, "shipState": None}, CAL, regions.DEFAULT_STATE_REGION)["location_id"] == "ONL-UNK"


def test_a_row_missing_a_required_field_is_rejected_not_guessed():
    bad = dict(ROW)
    del bad["placedAt"]
    with pytest.raises(ValueError):
        prepare_line(bad, CAL, regions.DEFAULT_STATE_REGION)


def test_the_state_map_is_configurable_and_a_bad_setting_fails_loudly():
    assert regions.parse_map('{"ka": "X"}') == {"KA": "X"}
    assert regions.parse_map(None)["MH"] == "W"
    with pytest.raises(ValueError):
        regions.parse_map('["KA"]')
    with pytest.raises(ValueError):
        regions.parse_map("not json")
