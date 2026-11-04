from __future__ import annotations

from decimal import Decimal

import pytest

from app.sourcing.calc import CostInputs, PriceBreak, compare_quantities, compute, parse_inputs, sensitivity, unit_price_at

D = Decimal


def base(**kw) -> CostInputs:
    # 500 units at USD 10, 1 USD = INR 83: purchase 415,000. Freight 20,000. Duty 10% of 435,000 = 43,500. Handling+packaging (5+2) x 500 = 3,500. Other 1,500.
    values = dict(quantity=500, unit_price=D("10"), currency="USD", fx_rate=D("83"), fx_source="test rate", freight_total=D("20000"), duty_pct=D("10"),
                  handling_per_unit=D("5"), packaging_per_unit=D("2"), other_fixed_costs=D("1500"))
    values.update(kw)
    return CostInputs(**values)


def test_landed_cost_matches_hand_calculation():
    r = compute(base())
    assert r.status == "OK"
    assert r.values["purchase_cost"] == "415000.00" and r.values["duties"] == "43500.00" and r.values["handling_and_packaging"] == "3500.00"
    assert r.values["total_procurement_cost"] == "483500.00" and r.values["landed_cost_per_unit"] == "967.00"


def test_margin_is_computed_on_net_revenue_and_markup_is_separate():
    r = compute(base(selling_price=D("1500"), price_includes_tax=True, sales_tax_pct=D("18")))
    assert r.values["net_revenue_per_unit"] == "1271.19"
    assert r.values["gross_profit_per_unit"] == "304.19"
    assert r.values["gross_margin_pct"] == "23.9" and r.values["markup_pct"] == "31.5"


def test_tax_exclusive_price_is_used_as_is():
    r = compute(base(selling_price=D("1500")))
    assert r.values["net_revenue_per_unit"] == "1500.00" and r.values["gross_margin_pct"] == "35.5"


def test_defect_allowance_spreads_cost_over_sellable_units():
    r = compute(base(defect_pct=D("10")))
    assert r.values["sellable_units"] == "450.00" and r.values["landed_cost_per_unit"] == "1074.44"


def test_break_even_and_target_margin_prices():
    r = compute(base(selling_price=D("1500"), target_margin_pct=D("30"), variable_cost_pct=D("2"), variable_cost_per_unit=D("10")))
    # break-even net = (967 + 10) / 0.98 ; target net = 977 / (1 - 0.02 - 0.30)
    assert r.values["break_even_net_price"] == "996.94"
    assert r.values["target_margin_net_price"] == "1436.76"


def test_target_margin_price_gross_up_for_tax_inclusive_selling():
    r = compute(base(selling_price=D("1500"), price_includes_tax=True, sales_tax_pct=D("18"), target_margin_pct=D("30")))
    assert r.values["target_margin_net_price"] == "1381.43" and r.values["target_margin_selling_price"] == "1630.09"


def test_price_breaks_pick_the_right_tier_and_the_cheapest_unit_is_not_always_cheapest_landed():
    breaks = (PriceBreak(100, D("12")), PriceBreak(500, D("10")), PriceBreak(1000, D("9")))
    assert unit_price_at(50, D("14"), breaks)[0] == D("14")
    assert unit_price_at(100, D("14"), breaks)[0] == D("12")
    assert unit_price_at(999, D("14"), breaks)[0] == D("10")
    assert unit_price_at(1000, D("14"), breaks)[0] == D("9")
    # supplier A is cheaper per unit but charges heavy freight; B costs more per unit with cheap freight: B has the lower landed cost
    a = compute(base(unit_price=D("9.5"), freight_total=D("90000")))
    b = compute(base(unit_price=D("10"), freight_total=D("10000")))
    assert Decimal(a.values["unit_price"]) < Decimal(b.values["unit_price"])
    assert Decimal(a.values["landed_cost_per_unit"]) > Decimal(b.values["landed_cost_per_unit"])


def test_below_moq_is_invalid_not_silently_computed():
    r = compute(base(moq=1000))
    assert r.status == "INVALID" and "minimum order quantity" in r.issues[0] and "landed_cost_per_unit" not in r.values


def test_missing_inputs_make_it_incomplete_and_name_them():
    r = compute(CostInputs(currency="USD"))
    assert r.status == "INCOMPLETE"
    assert "quantity" in r.missing and "unit_price" in r.missing and any(m.startswith("fx_rate") for m in r.missing)


def test_foreign_currency_without_fx_is_incomplete_never_guessed():
    r = compute(base(fx_rate=None))
    assert r.status == "INCOMPLETE" and any("fx_rate" in m for m in r.missing)


def test_selling_price_missing_still_gives_cost_and_says_margin_is_not_computed():
    r = compute(base())
    assert r.status == "OK" and r.missing_for_margin == ["selling_price"] and "gross_margin_pct" not in r.values


@pytest.mark.parametrize("change", [dict(quantity=0), dict(quantity=-5), dict(unit_price=D("0")), dict(unit_price=D("-1")), dict(freight_total=D("-1")), dict(defect_pct=D("100")),
                                    dict(defect_pct=D("-1")), dict(fx_rate=D("0")), dict(variable_cost_pct=D("100")), dict(selling_price=D("-3")), dict(duty_pct=D("-2"))])
def test_impossible_inputs_are_invalid(change):
    assert compute(base(**change)).status == "INVALID"


def test_loss_making_price_is_flagged():
    r = compute(base(selling_price=D("500")))
    assert r.values["gross_profit_per_unit"].startswith("-") and any("loses money" in i for i in r.issues)


def test_compare_quantities_applies_breaks_and_moq_per_quantity():
    inputs = base(price_breaks=(PriceBreak(500, D("9")),), moq=100)
    rows = compare_quantities(inputs, [50, 100, 500])
    assert rows[0]["status"] == "INVALID"                                   # below MOQ
    assert rows[1]["values"]["unit_price"] == "10.00" and rows[2]["values"]["unit_price"] == "9.00"


def test_sensitivity_varies_only_what_is_set_and_reports_change():
    rows = sensitivity(base(selling_price=D("1500")))
    names = {r["scenario"] for r in rows}
    assert {"freight +20%", "freight -20%", "USD +5% against INR"} <= names
    up = next(r for r in rows if r["scenario"] == "freight +20%")
    assert Decimal(up["change_pct"]) > 0
    domestic = sensitivity(base(currency="INR", fx_rate=None, freight_total=D("0")))
    assert not any("freight" in r["scenario"] or "against" in r["scenario"] for r in domestic)


def test_cost_drivers_sum_to_the_total_share():
    r = compute(base())
    assert round(sum(Decimal(d["share_pct"]) for d in r.drivers), 0) == 100


def test_parse_inputs_rejects_garbage_and_accepts_strings():
    with pytest.raises(ValueError):
        parse_inputs({"quantity": 5, "unit_price": "abc"})
    p = parse_inputs({"quantity": "500", "unit_price": "10", "currency": "usd", "fx_rate": "83", "price_breaks": [{"min_quantity": 1000, "unit_price": "9"}]})
    assert p.quantity == 500 and p.currency == "USD" and p.price_breaks[0].min_quantity == 1000
