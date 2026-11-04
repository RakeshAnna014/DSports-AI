"""Deterministic landed-cost and profitability calculator for Product & Supplier Intelligence.

Pure functions over Decimal. No LLM, no I/O, no hidden defaults for money: a missing required input makes the result INCOMPLETE and says which one; an impossible input
makes it INVALID. Every result carries the assumptions it used so a saved snapshot can be reviewed later.

Cost model (all amounts end up in the base currency, INR by default):

    purchase cost            = unit price at the order quantity x quantity          (price breaks: the highest tier whose minimum quantity <= quantity)
    purchase cost (base)     = purchase cost x fx rate                               (fx rate required when the quote currency differs; it is never invented)
    customs value            = purchase cost (base) + freight                        (assumption: duty is charged on cost + freight; stated in the result)
    duties                   = customs value x duty %
    non-recoverable taxes    = (customs value + duties) x non-recoverable tax %      (recoverable input tax is NOT a cost: leave this at 0 for it)
    handling and packaging   = (handling per unit + packaging per unit) x quantity
    total procurement cost   = purchase (base) + freight + duties + non-recoverable taxes + handling and packaging + other fixed costs
    sellable units           = quantity x (1 - defect/loss %)                        (the whole procurement cost is spread over units that can be sold)
    landed cost per unit     = total procurement cost / sellable units

    net revenue per unit     = selling price / (1 + tax %) when the selling price includes tax, else the selling price
    variable cost per unit   = fixed per-unit variable cost + net revenue x variable cost % (e.g. payment fees)
    gross profit per unit    = net revenue - landed cost - variable cost
    gross margin %           = gross profit / net revenue x 100
    markup %                 = gross profit / landed cost x 100                      (a different number from margin; both are reported, never mixed up)
    break-even net price     = (landed + fixed variable cost) / (1 - variable cost %)
    target-margin net price  = (landed + fixed variable cost) / (1 - variable cost % - target margin %)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any, Mapping, Sequence

CENT = Decimal("0.01")
HUNDRED = Decimal(100)
ZERO = Decimal(0)


def _d(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise ValueError("a boolean is not a number")
    try:
        d = Decimal(str(value))
    except InvalidOperation as exc:
        raise ValueError(f"not a number: {value!r}") from exc
    if not d.is_finite():
        raise ValueError(f"not a finite number: {value!r}")
    return d


def money(value: Decimal | None) -> str | None:
    return None if value is None else str(value.quantize(CENT, rounding=ROUND_HALF_UP))


@dataclass(frozen=True)
class PriceBreak:
    min_quantity: int
    unit_price: Decimal


@dataclass
class CostInputs:
    quantity: int | None = None
    unit_price: Decimal | None = None                  # at the quote currency; ignored when price_breaks cover the quantity
    price_breaks: Sequence[PriceBreak] = ()
    moq: int | None = None
    currency: str = "INR"
    base_currency: str = "INR"
    fx_rate: Decimal | None = None                     # 1 unit of `currency` in `base_currency`
    fx_source: str | None = None
    fx_as_of: str | None = None
    freight_total: Decimal = ZERO                      # already in the base currency
    duty_pct: Decimal = ZERO
    non_recoverable_tax_pct: Decimal = ZERO
    handling_per_unit: Decimal = ZERO
    packaging_per_unit: Decimal = ZERO
    other_fixed_costs: Decimal = ZERO
    defect_pct: Decimal = ZERO
    selling_price: Decimal | None = None
    price_includes_tax: bool = False
    sales_tax_pct: Decimal = ZERO
    variable_cost_per_unit: Decimal = ZERO
    variable_cost_pct: Decimal = ZERO                  # of net revenue
    target_margin_pct: Decimal | None = None


@dataclass
class CostResult:
    status: str                                         # OK | INCOMPLETE | INVALID
    issues: list[str] = field(default_factory=list)     # why it is INVALID or what a warning is
    missing: list[str] = field(default_factory=list)    # inputs needed before the cost can be computed
    missing_for_margin: list[str] = field(default_factory=list)
    values: dict[str, Any] = field(default_factory=dict)
    assumptions: list[str] = field(default_factory=list)
    drivers: list[dict[str, Any]] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {"status": self.status, "issues": self.issues, "missing": self.missing, "missing_for_margin": self.missing_for_margin, "values": self.values,
                "assumptions": self.assumptions, "cost_drivers": self.drivers}


def parse_inputs(raw: Mapping[str, Any]) -> CostInputs:
    """From an API payload. Raises ValueError with a readable message for non-numeric values."""
    def pct(key: str) -> Decimal:
        return _d(raw.get(key)) or ZERO

    breaks = tuple(sorted((PriceBreak(int(b["min_quantity"]), _d(b["unit_price"]) or ZERO) for b in (raw.get("price_breaks") or [])), key=lambda b: b.min_quantity))
    qty = raw.get("quantity")
    return CostInputs(
        quantity=None if qty in (None, "") else int(qty), unit_price=_d(raw.get("unit_price")), price_breaks=breaks, moq=None if raw.get("moq") in (None, "") else int(raw["moq"]),
        currency=str(raw.get("currency") or "INR").upper(), base_currency=str(raw.get("base_currency") or "INR").upper(), fx_rate=_d(raw.get("fx_rate")),
        fx_source=raw.get("fx_source"), fx_as_of=raw.get("fx_as_of"), freight_total=_d(raw.get("freight_total")) or ZERO, duty_pct=pct("duty_pct"),
        non_recoverable_tax_pct=pct("non_recoverable_tax_pct"), handling_per_unit=_d(raw.get("handling_per_unit")) or ZERO, packaging_per_unit=_d(raw.get("packaging_per_unit")) or ZERO,
        other_fixed_costs=_d(raw.get("other_fixed_costs")) or ZERO, defect_pct=pct("defect_pct"), selling_price=_d(raw.get("selling_price")),
        price_includes_tax=bool(raw.get("price_includes_tax", False)), sales_tax_pct=pct("sales_tax_pct"), variable_cost_per_unit=_d(raw.get("variable_cost_per_unit")) or ZERO,
        variable_cost_pct=pct("variable_cost_pct"), target_margin_pct=_d(raw.get("target_margin_pct")))


def unit_price_at(quantity: int, base: Decimal | None, breaks: Sequence[PriceBreak]) -> tuple[Decimal | None, str]:
    """The tier whose minimum quantity is the highest one not above `quantity`; below every tier the base price applies."""
    tier = None
    for b in sorted(breaks, key=lambda x: x.min_quantity):
        if b.min_quantity <= quantity:
            tier = b
    if tier is not None:
        return tier.unit_price, f"price break from {tier.min_quantity} units"
    return base, "base unit price"


def compute(i: CostInputs) -> CostResult:
    r = CostResult(status="OK")
    # ---- validation: impossible values first, then what is merely missing
    for name, v in (("freight_total", i.freight_total), ("duty_pct", i.duty_pct), ("non_recoverable_tax_pct", i.non_recoverable_tax_pct), ("handling_per_unit", i.handling_per_unit),
                    ("packaging_per_unit", i.packaging_per_unit), ("other_fixed_costs", i.other_fixed_costs), ("sales_tax_pct", i.sales_tax_pct),
                    ("variable_cost_per_unit", i.variable_cost_per_unit), ("variable_cost_pct", i.variable_cost_pct)):
        if v < 0:
            r.issues.append(f"{name} cannot be negative")
    if not (ZERO <= i.defect_pct < HUNDRED):
        r.issues.append("defect_pct must be at least 0 and below 100")
    if i.quantity is not None and i.quantity <= 0:
        r.issues.append("quantity must be a positive whole number")
    if i.unit_price is not None and i.unit_price <= 0:
        r.issues.append("unit_price must be greater than zero")
    if i.selling_price is not None and i.selling_price <= 0:
        r.issues.append("selling_price must be greater than zero")
    if i.fx_rate is not None and i.fx_rate <= 0:
        r.issues.append("fx_rate must be greater than zero")
    if i.variable_cost_pct >= HUNDRED:
        r.issues.append("variable_cost_pct must be below 100")
    if i.target_margin_pct is not None and not (ZERO < i.target_margin_pct < HUNDRED - i.variable_cost_pct):
        r.issues.append("target_margin_pct must be above 0 and, with the variable cost %, below 100")
    if r.issues:
        r.status = "INVALID"
        return r

    if i.quantity is None:
        r.missing.append("quantity")
    price, price_basis = (None, "")
    if i.quantity is not None:
        price, price_basis = unit_price_at(i.quantity, i.unit_price, i.price_breaks)
    if price is None:
        r.missing.append("unit_price")
    needs_fx = i.currency != i.base_currency
    if needs_fx and i.fx_rate is None:
        r.missing.append(f"fx_rate ({i.currency} to {i.base_currency}) from a stated source")
    if r.missing:
        r.status = "INCOMPLETE"
        return r
    assert i.quantity is not None and price is not None

    if i.moq is not None and i.quantity < i.moq:
        r.status = "INVALID"
        r.issues.append(f"quantity {i.quantity} is below the supplier's minimum order quantity of {i.moq}")
        return r

    fx = i.fx_rate if needs_fx else Decimal(1)
    qty = Decimal(i.quantity)
    purchase_quote = price * qty
    purchase = purchase_quote * fx
    customs_value = purchase + i.freight_total
    duties = customs_value * i.duty_pct / HUNDRED
    taxes = (customs_value + duties) * i.non_recoverable_tax_pct / HUNDRED
    handling = (i.handling_per_unit + i.packaging_per_unit) * qty
    total = purchase + i.freight_total + duties + taxes + handling + i.other_fixed_costs
    sellable = qty * (HUNDRED - i.defect_pct) / HUNDRED
    landed = total / sellable

    v = r.values
    v.update({"quantity": i.quantity, "unit_price": money(price), "price_basis": price_basis, "quote_currency": i.currency, "base_currency": i.base_currency,
              "fx_rate": None if not needs_fx else str(fx), "purchase_cost_quote_currency": money(purchase_quote), "purchase_cost": money(purchase), "freight": money(i.freight_total),
              "duties": money(duties), "non_recoverable_taxes": money(taxes), "handling_and_packaging": money(handling), "other_fixed_costs": money(i.other_fixed_costs),
              "total_procurement_cost": money(total), "sellable_units": str(sellable.quantize(Decimal("0.01"))), "landed_cost_per_unit": money(landed)})
    r.assumptions += [f"Unit price from the {price_basis}.",
                      "Duty is charged on purchase cost plus freight; non-recoverable taxes on cost, freight and duty. Recoverable input tax is not a cost.",
                      f"Defect/loss allowance {i.defect_pct}%: the whole procurement cost is spread over {sellable.quantize(Decimal('0.01'))} sellable units." if i.defect_pct else
                      "No defect or loss allowance was applied (0%)."]
    if needs_fx:
        r.assumptions.append(f"1 {i.currency} = {fx} {i.base_currency}" + (f" ({i.fx_source}" + (f", {i.fx_as_of}" if i.fx_as_of else "") + ")" if i.fx_source else " (source not stated)"))
    parts = [("Product purchase", purchase), ("Freight", i.freight_total), ("Duties", duties), ("Non-recoverable taxes", taxes), ("Handling and packaging", handling), ("Other fixed costs", i.other_fixed_costs)]
    r.drivers = [{"component": n, "amount": money(a), "share_pct": str((a / total * HUNDRED).quantize(Decimal("0.1"))) if total else "0.0"} for n, a in parts if a]
    if i.moq is not None:
        v["moq"] = i.moq

    fixed_var = i.variable_cost_per_unit
    var_pct = i.variable_cost_pct / HUNDRED
    v["break_even_net_price"] = money((landed + fixed_var) / (1 - var_pct))
    v["break_even_selling_price"] = money(_gross_up((landed + fixed_var) / (1 - var_pct), i))
    if i.target_margin_pct is not None:
        target_net = (landed + fixed_var) / (1 - var_pct - i.target_margin_pct / HUNDRED)
        v["target_margin_pct"] = str(i.target_margin_pct)
        v["target_margin_net_price"] = money(target_net)
        v["target_margin_selling_price"] = money(_gross_up(target_net, i))

    if i.selling_price is None:
        r.missing_for_margin.append("selling_price")
        return r
    net = i.selling_price / (1 + i.sales_tax_pct / HUNDRED) if i.price_includes_tax else i.selling_price
    variable = fixed_var + net * var_pct
    profit = net - landed - variable
    v.update({"selling_price": money(i.selling_price), "price_includes_tax": i.price_includes_tax, "sales_tax_pct": str(i.sales_tax_pct), "net_revenue_per_unit": money(net),
              "variable_cost_per_unit": money(variable), "gross_profit_per_unit": money(profit), "gross_margin_pct": str((profit / net * HUNDRED).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)),
              "markup_pct": str((profit / landed * HUNDRED).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)), "total_gross_profit": money(profit * sellable)})
    r.assumptions.append("Margin is gross profit / net revenue (tax excluded); markup is gross profit / landed cost. They are different numbers.")
    if profit < 0:
        r.issues.append("At this selling price the product loses money per unit.")
    return r


def _gross_up(net: Decimal, i: CostInputs) -> Decimal:
    return net * (1 + i.sales_tax_pct / HUNDRED) if i.price_includes_tax else net


def compare_quantities(base: CostInputs, quantities: Sequence[int]) -> list[dict[str, Any]]:
    """The same inputs at several order quantities (price breaks and MOQ apply to each)."""
    out = []
    for q in quantities:
        res = compute(CostInputs(**{**base.__dict__, "quantity": q}))
        out.append({"quantity": q, **res.as_dict()})
    return out


def sensitivity(base: CostInputs) -> list[dict[str, Any]]:
    """What the landed cost does when one important input moves. Only inputs that are actually set are varied."""
    ref = compute(base)
    if ref.status != "OK":
        return []
    ref_landed = Decimal(ref.values["landed_cost_per_unit"])
    rows: list[dict[str, Any]] = []

    def run(label: str, **changes: Any) -> None:
        res = compute(CostInputs(**{**base.__dict__, **changes}))
        if res.status != "OK":
            return
        landed = Decimal(res.values["landed_cost_per_unit"])
        rows.append({"scenario": label, "landed_cost_per_unit": money(landed), "change_pct": str(((landed - ref_landed) / ref_landed * HUNDRED).quantize(Decimal("0.1"))),
                     "gross_margin_pct": res.values.get("gross_margin_pct")})

    if base.freight_total > 0:
        run("freight +20%", freight_total=base.freight_total * Decimal("1.2"))
        run("freight -20%", freight_total=base.freight_total * Decimal("0.8"))
    if base.currency != base.base_currency and base.fx_rate:
        run(f"{base.currency} +5% against {base.base_currency}", fx_rate=base.fx_rate * Decimal("1.05"))
        run(f"{base.currency} -5% against {base.base_currency}", fx_rate=base.fx_rate * Decimal("0.95"))
    if base.quantity:
        run("defect/loss +2 points", defect_pct=min(base.defect_pct + 2, Decimal("99")))
        run("quantity +50%", quantity=int(base.quantity * 1.5))
    return rows
