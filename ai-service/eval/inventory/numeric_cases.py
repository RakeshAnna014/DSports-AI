"""Deterministic Inventory Intelligence evaluation: stock risk, replenishment, promotion scenarios and forecasting. NO model, NO database.

Expected values for the stock-risk and promotion cases were CALCULATED BY HAND from the documented formulas (the arithmetic is in the
comment above each case), not copied from the code's output. DEV cases were used while building; HOLDOUT cases were written afterwards
with different parameters and are reported separately. A failing holdout case is reported as a failure; it is not edited to pass.

Stock-risk formulas (planning.py): cover = first day the projected stock reaches 0 (fractional); safety stock = ceil(1.645 * sigma * sqrt(LT));
target = demand over (LT + 7 review days) + safety stock; qty = target - (on hand + incoming), rounded up to case pack, lifted to MOQ.
HIGH if cover < LT or P(run out before replenishment) >= 0.5; MEDIUM if cover < LT + 7 or P >= 0.2; else LOW.
Promotion formulas (scenarios.py): demand = daily * days * (1 - x) ** (-e); sold = min(demand, stock available inside the window);
revenue = sold * price * (1 - x); margin = sold * (price * (1 - x) - cost).
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np

from app.inventory import forecasting as F
from app.inventory import planning as P
from app.inventory import scenarios as S

AS_OF = date(2026, 10, 8)
START = date(2024, 1, 1)


# ------------------------------------------------------------------------------------------------------------------ stock risk
def risk(on_hand, daily, lt, sigma, arrivals=(), moq=None, case=None, expect=None, name="", **extra):
    return {"id": name, "kind": "risk", "on_hand": on_hand, "daily": daily, "lt": lt, "sigma": sigma, "arrivals": arrivals, "moq": moq, "case": case, "expect": expect, **extra}


RISK_DEV = [
    # R1: cover 50/12 = 4.17 (< LT 7) HIGH; SS = ceil(1.645*3*sqrt7 = 13.06) = 14; target = 12*14 + 14 = 182; qty = 182 - 50 = 132
    risk(50, 12, 7, 3, expect={"risk": "HIGH", "cover": 4.2, "qty": 132, "action": "ORDER"}, name="R1 the brief's example: 50 in stock, 12 a day, 7 day lead time"),
    # R2: cover 500/10 = 50; SS = ceil(1.645*2*sqrt7 = 8.70) = 9; target 149 < 500 -> no order
    risk(500, 10, 7, 2, expect={"risk": "LOW", "cover": 50.0, "qty": 0}, name="R2 plenty of stock"),
    # R3: cover 10 < LT 14 HIGH; SS = ceil(1.645*2*sqrt14 = 12.31) = 13; target = 10*21 + 13 = 223; qty 123
    risk(100, 10, 14, 2, expect={"risk": "HIGH", "cover": 10.0, "qty": 123}, name="R3 long lead time"),
    # R4: 100 now, 200 arrive on day 3: stock hits zero on day 29 + 10/10 = 30.0; LT 5; position 300; target = 10*12 + ceil(1.645*1*sqrt5 = 3.68) 4 = 124 < 300
    risk(100, 10, 5, 1, arrivals=((3, 200),), expect={"risk": "LOW", "cover": 30.0, "qty": 0}, name="R4 open order arrives in time"),
    # R5: cover 12 (< LT+7 = 15, >= LT 8) MEDIUM; SS = ceil(1.645*2.5*sqrt8 = 11.63) = 12; target = 150 + 12 = 162; qty 42;
    #     projected stock <= 12 after day index 10 (120 - 110 = 10) so order_by = today + max(0, 10 - 8) = +2
    risk(120, 10, 8, 2.5, expect={"risk": "MEDIUM", "cover": 12.0, "qty": 42, "order_offset": 2}, name="R5 medium risk, order can wait two days"),
    # R6: cover 6 < 10 HIGH; SS = ceil(1.645*1*sqrt10 = 5.2) = 6; target = 5*17 + 6 = 91; need 61 -> case pack 6 -> 66 (>= MOQ 48)
    risk(30, 5, 10, 1, moq=48, case=6, expect={"risk": "HIGH", "cover": 6.0, "qty": 66}, name="R6 rounded up to the case pack"),
    # R7: cover 12 < 13 MEDIUM; SS = ceil(1.645*0.5*sqrt6 = 2.01) = 3; target = 5*13 + 3 = 68; need 8 -> case 6 -> 12 -> lifted to MOQ 24
    risk(60, 5, 6, 0.5, moq=24, case=6, expect={"risk": "MEDIUM", "cover": 12.0, "qty": 24}, name="R7 lifted to the minimum order quantity"),
    # R8: no demand: never runs out; coverage is unbounded; all 40 units are excess over a zero forecast
    risk(40, 0, 7, 0.1, expect={"risk": "LOW", "cover": None, "qty": 0, "excess": 40, "overstock": "HIGH"}, name="R8 no demand at all"),
    # R9: 20 now, 400 arrive on day 1: zero at day 41 + 10/10 = 42.0; LT 7: stays covered; position 420 >> target
    risk(20, 10, 7, 1, arrivals=((1, 400),), expect={"risk": "LOW", "cover": 42.0, "qty": 0}, name="R9 open order lands tomorrow"),
    # R10: 30 on hand (3.0 days), the open 500 only arrives on day 20 -> HIGH; position 530 covers the target, so NO new order: expedite instead
    risk(30, 10, 5, 1, arrivals=((20, 500),), expect={"risk": "HIGH", "cover": 3.0, "qty": 0, "action": "EXPEDITE_INCOMING"}, name="R10 the open order is too late: expedite, do not re-order"),
]
RISK_HOLDOUT = [
    # H1: cover exactly 10.0 = LT 10 (not < LT) but P(run out) = 0.5 -> HIGH; SS = ceil(1.645*4*sqrt10 = 20.81) = 21; target = 20*17 + 21 = 361; qty 161
    risk(200, 20, 10, 4, expect={"risk": "HIGH", "cover": 10.0, "qty": 161}, name="H1 cover equals the lead time: a coin flip is HIGH"),
    # H2: cover 15 < 17 MEDIUM; qty = 361 - 300 = 61; projected <= 21 after day index 13 (300 - 280 = 20) -> order_by = +max(0, 13 - 10) = +3
    risk(300, 20, 10, 4, expect={"risk": "MEDIUM", "cover": 15.0, "qty": 61, "order_offset": 3}, name="H2 medium"),
    risk(1000, 20, 10, 4, expect={"risk": "LOW", "cover": 50.0, "qty": 0}, name="H3 comfortable"),
    # H4: nothing on hand: cover 0.0; SS = ceil(1.645*1*sqrt6 = 4.03) = 5; target = 8*13 + 5 = 109
    risk(0, 8, 6, 1, expect={"risk": "HIGH", "cover": 0.0, "qty": 109}, name="H4 already out of stock"),
    # H5: SS = ceil(1.645*0.5*sqrt20 = 3.68) = 4; target = 1*27 + 4 = 31; need 26 (>= MOQ 6)
    risk(5, 1, 20, 0.5, moq=6, case=1, expect={"risk": "HIGH", "cover": 5.0, "qty": 26}, name="H5 slow seller, very long lead time"),
    # H6: cover 5.0 = LT 5 and P = 0.5 -> HIGH; SS = ceil(1.645*0.3*sqrt5 = 1.10) = 2; target = 2*12 + 2 = 26; need 16 -> case 10 -> 20
    risk(10, 2, 5, 0.3, case=10, expect={"risk": "HIGH", "cover": 5.0, "qty": 20}, name="H6 case pack of 10"),
    # H7: 40 now, 100 on day 2: stock 20 at day 2 -> 120; zero at day 13 + 10/10 = 14.0 (< LT+7 = 17) MEDIUM; SS = ceil(1.645*1*sqrt10 = 5.2) = 6;
    #     target = 10*17 + 6 = 176; position 140 -> 36; projected <= 6 after day index 13 (110 - 110 = 0) -> order_by = +max(0, 13 - 10) = +3
    risk(40, 10, 10, 1, arrivals=((2, 100),), expect={"risk": "MEDIUM", "cover": 14.0, "qty": 36, "order_offset": 3}, name="H7 an order lands inside the lead time"),
    # H8: out of stock today; 300 arrive tomorrow -> HIGH (P ~ 1 before the arrival); position 300 >= target 75 -> expedite, no new order
    risk(0, 5, 7, 1, arrivals=((1, 300),), expect={"risk": "HIGH", "cover": 0.0, "qty": 0, "action": "EXPEDITE_INCOMING"}, name="H8 out of stock, delivery tomorrow"),
    # H9: no supplier lead time on record: cover 3.0 -> HIGH from cover alone; no order quantity is invented
    risk(30, 10, None, 1, expect={"risk": "HIGH", "cover": 3.0, "status": "MISSING_LEAD_TIME", "qty": None}, name="H9 missing lead time"),
    # H10: no forecast available: nothing is calculated
    risk(30, None, 7, 1, expect={"status": "INSUFFICIENT_DATA", "risk": None, "qty": None}, name="H10 no forecast: insufficient data"),
]


def run_risk(c: dict) -> list[str]:
    daily = None if c["daily"] is None else np.full(90, float(c["daily"]))
    a = P.assess(AS_OF, P.Position(c["on_hand"], tuple(c["arrivals"])), P.Reference(c["lt"], c["moq"], c["case"], 100.0, 200.0), daily, c["sigma"])
    e, bad = c["expect"], []
    if "status" in e and a.status != e["status"]:
        bad.append(f"status {a.status} != {e['status']}")
    if "risk" in e and a.risk_level != e["risk"]:
        bad.append(f"risk {a.risk_level} != {e['risk']}")
    if "cover" in e and a.days_of_cover != e["cover"]:
        bad.append(f"cover {a.days_of_cover} != {e['cover']}")
    if "qty" in e and a.reorder_qty != e["qty"]:
        bad.append(f"qty {a.reorder_qty} != {e['qty']}")
    if "action" in e and a.detail.get("action") != e["action"]:
        bad.append(f"action {a.detail.get('action')} != {e['action']}")
    if "order_offset" in e and (a.order_by is None or (a.order_by - AS_OF).days != e["order_offset"]):
        bad.append(f"order_by +{None if a.order_by is None else (a.order_by - AS_OF).days} != +{e['order_offset']}")
    if "excess" in e and a.excess_units != e["excess"]:
        bad.append(f"excess {a.excess_units} != {e['excess']}")
    if "overstock" in e and a.overstock_level != e["overstock"]:
        bad.append(f"overstock {a.overstock_level} != {e['overstock']}")
    return bad


# ------------------------------------------------------------------------------------------------------------------ promotions
def promo(name, expect, daily=10.0, days=14, on_hand=100_000, arrivals=(), price=1000.0, cost=600.0, e=2.0, lo=None, hi=None, x=0.3, age=0, excess=0, pf=0.1):
    return {"id": name, "kind": "promo", "daily": daily, "days": days, "on_hand": on_hand, "arrivals": arrivals, "price": price, "cost": cost, "e": e,
            "lo": lo if lo is not None else e * 0.8, "hi": hi if hi is not None else e * 1.2, "x": x, "age": age, "excess": excess, "pf": pf, "expect": expect}


PROMO_DEV = [
    # P1: base 140; 0.7^-2 = 2.0408 -> 285.71 units; revenue 285.71*700 = 200,000; margin 285.71*(700-600) = 28,571
    promo("P1 30% off, elasticity 2", {"units": 285.7, "revenue": 200000.0, "margin": 28571.43, "unit_margin_pct": 14.3}),
    # P2: 0.5^-2 = 4 -> 560 units; price 500; revenue 280,000; margin 560*(500-600) = -56,000; unit margin -20%
    promo("P2 50% off turns the margin negative", {"units": 560.0, "revenue": 280000.0, "margin": -56000.0, "unit_margin_pct": -20.0}, x=0.5),
    # P3: capped at the 200 in stock: revenue 200*700 = 140,000; ending stock 0; stock-out risk HIGH
    promo("P3 stock caps the sales", {"units": 200.0, "revenue": 140000.0, "ending": 0.0, "stockout": "HIGH"}, on_hand=200),
    # P4: 0.8^-1.5 = 1.39754 -> 195.66 units; price 1600; revenue 313,050; margin 195.66*(1600-1000) = 117,394
    promo("P4 20% off, elasticity 1.5", {"units": 195.7, "revenue": 313049.6, "margin": 117393.8}, e=1.5, x=0.2, price=2000.0, cost=1000.0),
    # P5: 0% is the baseline: 140 units; revenue 140,000; margin 140*400 = 56,000
    promo("P5 zero discount is the baseline", {"units": 140.0, "revenue": 140000.0, "margin": 56000.0}, x=0.0, cost=600.0),
    # P6: 100 + 400 arriving on day 5 = 500 available < 560 demand -> sold 500, HIGH
    promo("P6 stock arriving inside the window counts", {"units": 500.0, "ending": 0.0, "stockout": "HIGH"}, on_hand=100, arrivals=((5, 400),), x=0.5),
    # P7: price 900 < cost 950: unit margin -5.56%
    promo("P7 discounting below cost", {"unit_margin_pct": -5.6}, price=1000.0, cost=950.0, x=0.1),
    # P8: base 140; promo 140*0.8^-2 = 218.75; extra 78.75; margin 65,625 vs 70,000 = -4,375; carrying 78.75*500*0.25*(1000/10=100 days)/365 = 2,696.9;
    #     write-down 78.75*500*0.40 (age 240) = 15,750 -> net benefit 14,071.9
    promo("P8 aged excess stock: the write-down avoided outweighs the margin given up", {"net_benefit": 14071.9}, on_hand=1000, price=1000.0, cost=500.0, x=0.2, age=240, excess=800, e=2.0),
    # P9: borrowed from the future = 0.1 * 78.75 = 7.875 -> 7.9
    promo("P9 pull-forward", {"borrowed": 7.9}, on_hand=1000, price=1000.0, cost=500.0, x=0.2, age=0, excess=0, pf=0.1),
    # P10: no cost on record: no margin, no net benefit, no invention
    promo("P10 missing cost", {"margin": None, "unit_margin_pct": None}, cost=None),
]
PROMO_HOLDOUT = [
    # HP1: 5*10 = 50 base; 1/0.8 = 1.25 -> 62.5; price 320; revenue 20,000; margin 62.5*120 = 7,500
    promo("HP1 elasticity 1", {"units": 62.5, "revenue": 20000.0, "margin": 7500.0}, daily=5.0, days=10, e=1.0, x=0.2, price=400.0, cost=200.0),
    # HP2: base 90; (0.75)^-2 = 16/9 -> 160; price 600; revenue 96,000; margin 160*200 = 32,000
    promo("HP2 25% off", {"units": 160.0, "revenue": 96000.0, "margin": 32000.0}, daily=9.0, days=10, e=2.0, x=0.25, price=800.0, cost=400.0),
    # HP3: base 20; 0.5^-3 = 8 -> 160; price 50; revenue 8,000; margin 160*20 = 3,200
    promo("HP3 high elasticity", {"units": 160.0, "revenue": 8000.0, "margin": 3200.0}, daily=2.0, days=10, e=3.0, x=0.5, price=100.0, cost=30.0),
    # HP4: base 30; 1/0.5 = 2 -> 60; price 125; revenue 7,500; margin 60*25 = 1,500
    promo("HP4 low elasticity", {"units": 60.0, "revenue": 7500.0, "margin": 1500.0}, daily=6.0, days=5, e=1.0, x=0.5, price=250.0, cost=100.0),
    # HP5: demand 10*10*4 = 400 but only 50 in stock -> 50 units; price 100; revenue 5,000; margin 50*20 = 1,000; ending 0
    promo("HP5 tiny stock", {"units": 50.0, "revenue": 5000.0, "margin": 1000.0, "ending": 0.0, "stockout": "HIGH"}, daily=10.0, days=10, e=2.0, x=0.5, price=200.0, cost=80.0, on_hand=50),
    # HP6: 7*10 = 70; revenue 21,000; margin 70*180 = 12,600
    promo("HP6 baseline", {"units": 70.0, "revenue": 21000.0, "margin": 12600.0}, daily=7.0, days=10, x=0.0, price=300.0, cost=120.0),
    # HP7: price 800, cost 700 -> 12.5%
    promo("HP7 unit margin at 20% off", {"unit_margin_pct": 12.5}, price=1000.0, cost=700.0, x=0.2),
    # HP8: 10 + 100 arriving on day 2 = 110 available; demand 5*10*2 = 100 -> sold 100; ending 10; ratio 100/110 = 0.91 -> MEDIUM; revenue 100*50 = 5,000
    promo("HP8 a delivery inside the window", {"units": 100.0, "revenue": 5000.0, "ending": 10.0, "stockout": "MEDIUM"}, daily=5.0, days=10, e=1.0, x=0.5, price=100.0, cost=40.0, on_hand=10, arrivals=((2, 100),)),
    # HP9: the delivery on day 12 is after the 10 day window: only the 10 on hand can be sold
    promo("HP9 a delivery after the window does not count", {"units": 10.0, "ending": 0.0, "stockout": "HIGH"}, daily=5.0, days=10, e=1.0, x=0.5, price=100.0, cost=40.0, on_hand=10, arrivals=((12, 100),)),
    # HP10: elasticity 2 (range 1-3) at 50% off on 100 base units: 400 (low 200, high 800)
    promo("HP10 the elasticity range becomes a units range", {"units": 400.0, "units_low": 200.0, "units_high": 800.0}, daily=10.0, days=10, e=2.0, lo=1.0, hi=3.0, x=0.5),
]


def run_promo(c: dict) -> list[str]:
    el = S.Elasticity("X", c["e"], c["lo"], c["hi"], c["pf"], 500, "HISTORICAL")
    item = S.Item("p", "P", c["price"], c["cost"], c["on_hand"], tuple(c["arrivals"]), tuple([c["daily"]] * 60), el, c["age"], c["excess"])
    e, bad = c["expect"], []
    if "net_benefit" in e:
        out = S.evaluate([item], [0.0, c["x"] * 100], c["days"])
        got = out["scenarios"][1]["net_benefit"]
        if abs(got - e["net_benefit"]) > max(2.0, abs(e["net_benefit"]) * 0.002):
            bad.append(f"net_benefit {got} != {e['net_benefit']}")
        return bad
    r = S.run_item(item, c["x"] * 100, c["days"])
    pairs = {"units": "units_sold", "revenue": "revenue", "margin": "margin", "unit_margin_pct": "unit_margin_pct", "ending": "ending_stock", "borrowed": "borrowed_from_future",
             "units_low": "units_low", "units_high": "units_high"}
    for k, field in pairs.items():
        if k in e:
            want, got = e[k], r[field]
            if want is None or got is None:
                if want != got:
                    bad.append(f"{k} {got} != {want}")
            elif abs(got - want) > max(0.15 if "pct" in k or k in ("units", "borrowed", "ending") else 5.0, abs(want) * 0.001):
                bad.append(f"{k} {got} != {want}")
    if "stockout" in e and r["stockout_risk"] != e["stockout"]:
        bad.append(f"stockout_risk {r['stockout_risk']} != {e['stockout']}")
    return bad


# ------------------------------------------------------------------------------------------------------------------ forecasting
def _dow(n):
    return np.array([(START + timedelta(days=i)).weekday() for i in range(n)])


def _series(n=730, base=20.0, weekend=1.5, trend=0.0, annual=0.0, noise="none", seed=1):
    rng = np.random.default_rng(seed)
    t = np.arange(n)
    mean = base * (1 + trend * t / 365) * np.where(_dow(n) >= 5, weekend, 1.0) * (1 + annual * np.sin(2 * np.pi * t / 365.25))
    return rng.poisson(mean).astype(float) if noise == "poisson" else mean, mean


def fc(name, check):
    return {"id": name, "kind": "forecast", "check": check}


def _wape(model, y, truth, horizon):
    return float(np.abs(F.predict(model, y, START, horizon) - truth[:horizon]).sum() / truth[:horizon].sum())


def f_weekly(seed):
    def run():
        y, m = _series(700, seed=seed)
        truth = _series(728, seed=seed)[1][700:728]
        worst = max(_wape(mod, y, truth, 28) for mod in ("seasonal_naive_7", "holt_winters", "ridge_regression"))
        return [] if worst < 0.05 else [f"weekly models WAPE {worst:.3f} >= 0.05"]
    return run


def f_trend(slope, seed=2):
    def run():
        y, _ = _series(500, weekend=1.0, trend=slope, seed=seed)
        truth = _series(560, weekend=1.0, trend=slope, seed=seed)[1][500:560]
        e = abs(F.predict("ridge_regression", y, START, 60).sum() - truth.sum()) / truth.sum()
        return [] if e < 0.10 else [f"60-day total off by {e:.1%} on a trend"]
    return run


def f_zero():
    def run():
        y = np.zeros(300)
        bad = [m for m in F.MODEL_NAMES if np.abs(F.predict(m, y, START, 30)).max() > 0.01]
        return [] if not bad else [f"{bad} forecast demand from zero history"]
    return run


def f_intermittent(rate, seed=5):
    def run():
        y = np.random.default_rng(seed).poisson(rate, 500).astype(float)
        out = F.predict("ridge_regression", y, START, 60)
        return [] if (out >= 0).all() and abs(out.mean() - rate) / rate < 0.35 else [f"intermittent mean {out.mean():.2f} vs {rate}"]
    return run


def f_annual(seed=3):
    def run():
        y, _ = _series(730, weekend=1.0, annual=0.4, seed=seed)
        truth = _series(820, weekend=1.0, annual=0.4, seed=seed)[1][730:820]
        ridge, flat = _wape("ridge_regression", y, truth, 90), _wape("moving_average_28", y, truth, 90)
        return [] if ridge < flat * 0.7 else [f"ridge {ridge:.3f} not clearly better than flat {flat:.3f} on annual seasonality"]
    return run


def f_stockout_masked(seed=4):
    def run():
        rng = np.random.default_rng(seed)
        n, level = 300, 30.0
        true = np.full(n, level)
        raw = rng.poisson(true).astype(float)
        out = rng.random(n) < 0.15
        raw[out] = 0.0                                                        # stock-out days record zero sales
        units = np.stack([raw * 0.5, raw * 0.5])
        so = np.stack([out, out])
        c = F.clean_series(units, so, np.zeros(n), np.full(n, -1), np.array([0.5, 0.5]))
        naive_err = abs(raw[-28:].mean() - level) / level
        clean_err = abs(F.predict("moving_average_28", c.y, START, 30).mean() - level) / level
        return [] if clean_err < 0.10 and clean_err < naive_err else [f"clean err {clean_err:.2%} vs naive {naive_err:.2%}"]
    return run


def f_promo_masked(seed=6):
    def run():
        rng = np.random.default_rng(seed)
        n = 300
        disc = np.zeros(n)
        disc[250:262] = 30
        raw = rng.poisson(np.where(disc > 0, 60, 20)).astype(float)
        c = F.clean_series(np.stack([raw * 0.5, raw * 0.5]), np.zeros((2, n), dtype=bool), disc, np.full(n, -1), np.array([0.5, 0.5]))
        got = F.predict("moving_average_28", c.y, START, 30).mean()
        return [] if 17 <= got <= 24 else [f"baseline after a promotion {got:.1f}, expected about 20"]
    return run


def f_history_too_short():
    def run():
        n = 30
        c = F.clean_series(np.full((1, n), 5.0), np.zeros((1, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([1.0]))
        return [] if int((~c.mask).sum()) < F.MIN_CLEAN_DAYS else ["30 days of history count as sufficient"]
    return run


def f_interval(seed=7):
    def run():
        rng = np.random.default_rng(seed)
        series = {}
        for k in range(30):
            y = rng.poisson(15, 500).astype(float)
            series[f"p{k}"] = F.Cleaned(y=y, mask=np.zeros(500, dtype=bool), raw=y, unusable=np.zeros(500, dtype=bool))
        sel, _ = F.run_backtest(series, START, (210, 180, 150, 120), 90, ("ridge_regression",))
        q = F.interval_quantiles(sel, "ridge_regression")
        hold, _ = F.run_backtest(series, START, (90,), 90, ("ridge_regression",))
        cov = F.coverage(hold, q, "ridge_regression", 30)
        return [] if cov is not None and 0.6 <= cov <= 0.97 else [f"80% interval covered {cov}"]
    return run


def f_level_shift(seed=8):
    def run():
        rng = np.random.default_rng(seed)
        y = np.concatenate([rng.poisson(10, 400), rng.poisson(15, 60)]).astype(float)
        ma, hw = F.predict("moving_average_28", y, START, 14).mean(), F.predict("holt_winters", y, START, 14).mean()
        return [] if 12 <= ma <= 18 and 11 <= hw <= 19 else [f"after a +50% level shift: MA {ma:.1f}, HW {hw:.1f}"]
    return run


FORECAST_DEV = [fc("F1 weekly pattern (seed 1)", f_weekly(1)), fc("F2 weekly pattern (seed 9)", f_weekly(9)), fc("F3 growing trend", f_trend(0.5)),
                fc("F4 zero demand", f_zero()), fc("F5 intermittent demand 0.3/day", f_intermittent(0.3)), fc("F6 annual season", f_annual()),
                fc("F7 stock-out days do not depress the forecast", f_stockout_masked()), fc("F8 a promotion does not inflate the baseline", f_promo_masked()),
                fc("F9 30 days of history is insufficient", f_history_too_short()), fc("F10 80% interval is roughly calibrated", f_interval())]
FORECAST_HOLDOUT = [fc("HF1 weekly pattern (seed 21)", f_weekly(21)), fc("HF2 weekly pattern (seed 33)", f_weekly(33)), fc("HF3 steeper trend", f_trend(0.9, seed=12)),
                    fc("HF4 intermittent demand 0.8/day", f_intermittent(0.8, seed=15)), fc("HF5 annual season (seed 23)", f_annual(23)),
                    fc("HF6 stock-outs (seed 24)", f_stockout_masked(24)), fc("HF7 promotion (seed 26)", f_promo_masked(26)), fc("HF8 interval calibration (seed 27)", f_interval(27)),
                    fc("HF9 level shift", f_level_shift()), fc("HF10 intermittent demand 0.15/day", f_intermittent(0.15, seed=18))]

SUITES = {
    "dev": {"stock_risk": RISK_DEV, "promotion": PROMO_DEV, "forecasting": FORECAST_DEV},
    "holdout": {"stock_risk": RISK_HOLDOUT, "promotion": PROMO_HOLDOUT, "forecasting": FORECAST_HOLDOUT},
}


def run_case(c: dict) -> list[str]:
    return run_risk(c) if c["kind"] == "risk" else run_promo(c) if c["kind"] == "promo" else c["check"]()
