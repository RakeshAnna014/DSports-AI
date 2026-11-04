"""POC SYNTHETIC historical data generator.

DSports does not yet hold usable history: the real database has ~5 weeks of mostly test orders, ONE warehouse, only a current
stock snapshot (no inventory movements), no purchase cost, no supplier / lead time and no regional dimension. Meaningful
forecasting needs at least a year of daily demand, so this module generates a reproducible, clearly labelled dataset:

  * deterministic: the same SKU list and `as_of` always give identical output (seeded per SKU, no global state);
  * every row written to the mart carries data_source = POC_SYNTHETIC (history, stock, purchase orders) or POC_REFERENCE
    (cost, supplier, lead time, MOQ, regions, events) and the API/UI always show that label;
  * realistic structure, so the forecasting and planning code is exercised honestly: trend, weekly and annual seasonality,
    sport seasons, tournament / marathon / Olympics uplift with a run-up, regional variation, fast and slow movers, declining
    products, promotions with a real price elasticity and a post-promotion dip, stock-outs that CENSOR demand, stock corrections,
    FIFO inventory age, open purchase orders, and a few injected data defects (missing days) for the data-quality checks;
  * a few SCRIPTED situations (stock at risk, overstock, slow) are pinned to named categories so the management demo has
    something to find. They are inputs of the POC, documented, and not discovered by the models.

Nothing here is a forecast. The models in forecasting.py never read the generator's parameters, only the generated history.
"""
from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

SEED = 20_261_008
HISTORY_DAYS = 730
SNAPSHOT_DAYS = 365
DATA_SOURCE = "POC_SYNTHETIC"
REFERENCE_SOURCE = "POC_REFERENCE"

REGIONS: dict[str, str] = {"N": "North", "S": "South", "E": "East", "W": "West", "C": "Central"}
REGION_CODES = list(REGIONS)
_BASE_SHARE = np.array([0.22, 0.27, 0.14, 0.25, 0.12])

# sport weight on volume, annual seasonality (amplitude, peak month) and regional tilt: POC assumptions
_SPORT_VOLUME = {"Cricket": 1.6, "Football": 1.3, "Running": 1.0, "Badminton": 0.9, "Fitness": 1.0, "Basketball": 0.7, "Tennis": 0.6,
                 "Volleyball": 0.5, "Swimming": 0.6, "Cycling": 0.5, "Table Tennis": 0.4}
_SPORT_SEASON = {"Cricket": (0.30, 2), "Football": (0.25, 9), "Running": (0.20, 11), "Badminton": (0.20, 1), "Fitness": (0.30, 1),
                 "Basketball": (0.15, 10), "Tennis": (0.20, 3), "Volleyball": (0.15, 8), "Swimming": (0.45, 5), "Cycling": (0.25, 11),
                 "Table Tennis": (0.10, 12)}
_SPORT_REGION_TILT = {"Cricket": [1.0, 1.15, 0.9, 1.2, 0.8], "Football": [0.8, 1.0, 1.5, 1.0, 0.9], "Running": [1.1, 1.2, 0.8, 1.1, 0.8],
                      "Badminton": [0.9, 1.5, 0.8, 0.9, 0.9], "Basketball": [1.2, 0.9, 0.8, 1.1, 1.0]}
_ELASTICITY = {"Bat": 1.5, "Ball": 2.0, "Gloves": 1.7, "Helmet": 1.3, "Jersey": 2.4, "Kit Bag": 1.8, "Shoes": 1.6, "Accessories": 2.6,
               "Equipment": 1.7, "Bike": 1.1, "Wearables": 1.2}
_EVENT_CATEGORY = {"Bat": 1.15, "Ball": 1.0, "Gloves": 1.05, "Helmet": 1.0, "Jersey": 1.25, "Kit Bag": 0.7, "Shoes": 0.9,
                   "Accessories": 0.8, "Equipment": 0.8, "Bike": 0.6, "Wearables": 0.7}
_EVENT_TYPE_UPLIFT = {"tournament": 1.9, "world_cup": 2.4, "marathon": 1.7, "olympics": 1.5}

_SUPPLIERS = [("Meerut Sports Works", 12), ("Jalandhar Athletics Co", 9), ("Bengaluru Sports Imports", 16), ("Mumbai Apparel Mills", 8),
              ("Guangzhou Sports Trading", 24), ("Delhi Equipment House", 6)]

AT_RISK, OVERSTOCK, SLOW, NORMAL = "AT_RISK", "OVERSTOCK", "SLOW", "NORMAL"
# scripted situations pinned to categories: (sport, category) -> (situation, how many of the SKUs, sorted by sku)
_PINNED: dict[tuple[str, str], tuple[str, int]] = {
    ("Cricket", "Bat"): (AT_RISK, 3), ("Cricket", "Gloves"): (AT_RISK, 2), ("Cricket", "Ball"): (AT_RISK, 1),
    ("Running", "Shoes"): (AT_RISK, 2), ("Football", "Shoes"): (AT_RISK, 1),
    ("Football", "Jersey"): (OVERSTOCK, 2), ("Basketball", "Jersey"): (OVERSTOCK, 3), ("Cricket", "Jersey"): (OVERSTOCK, 1),
    ("Cricket", "Kit Bag"): (OVERSTOCK, 1), ("Tennis", "Jersey"): (SLOW, 2), ("Badminton", "Jersey"): (SLOW, 2),
}


@dataclass(frozen=True)
class SkuRef:
    product_id: str
    sku: str
    name: str
    sport: str
    category: str
    brand: str | None
    price: float
    mrp: float | None
    dsports_available: int | None = None


@dataclass(frozen=True)
class EventSpec:
    id: str
    name: str
    event_type: str
    sport: str
    start_offset: int          # days from as_of to the first day of the demand window
    length: int
    popularity: float
    regions: dict[str, float]


# Historical (offset < 0) and upcoming (offset >= 0) events. The window is the DEMAND-IMPACT window (run-up included).
EVENT_SPECS: list[EventSpec] = [
    EventSpec("cricket-premier-2025", "Premier Cricket Tournament 2025 (POC)", "tournament", "Cricket", -590, 28, 1.0, {"W": 1.3, "S": 1.1, "N": 1.0}),
    EventSpec("cricket-worldcup-2026", "Cricket World Cup (POC)", "world_cup", "Cricket", -250, 35, 1.4, {"W": 1.2, "S": 1.2, "N": 1.1, "E": 1.0}),
    EventSpec("cricket-premier-2026", "Premier Cricket Tournament 2026 (POC)", "tournament", "Cricket", 28, 28, 1.2, {"W": 1.3, "S": 1.2, "N": 1.0}),
    EventSpec("football-championship-2025", "National Football Championship 2025 (POC)", "tournament", "Football", -500, 30, 1.0, {"E": 1.5, "S": 1.1}),
    EventSpec("football-cup-2026", "Football Cup 2026 (POC)", "tournament", "Football", -150, 30, 1.1, {"E": 1.4, "W": 1.0}),
    EventSpec("football-league-2026", "National Football League Season (POC)", "tournament", "Football", 50, 40, 1.0, {"E": 1.4, "S": 1.0}),
    EventSpec("tennis-open-2025", "Open Tennis Championship 2025 (POC)", "tournament", "Tennis", -400, 14, 1.0, {"N": 1.2, "S": 1.1}),
    EventSpec("tennis-open-2026", "Open Tennis Championship 2026 (POC)", "tournament", "Tennis", 75, 14, 1.1, {"N": 1.2, "S": 1.1}),
    EventSpec("marathon-2025", "City Marathon 2025 (POC)", "marathon", "Running", -330, 21, 1.0, {"N": 1.2, "S": 1.3, "W": 1.1}),
    EventSpec("marathon-2026", "City Marathon 2026 (POC)", "marathon", "Running", 60, 21, 1.1, {"N": 1.2, "S": 1.3, "W": 1.1}),
    EventSpec("olympics-2025-swim", "Olympics 2025 - Swimming (POC)", "olympics", "Swimming", -400, 17, 1.0, {}),
    EventSpec("olympics-2025-cycle", "Olympics 2025 - Cycling (POC)", "olympics", "Cycling", -400, 17, 1.0, {}),
    EventSpec("olympics-2025-tt", "Olympics 2025 - Table Tennis (POC)", "olympics", "Table Tennis", -400, 17, 1.0, {}),
    EventSpec("olympics-2025-badminton", "Olympics 2025 - Badminton (POC)", "olympics", "Badminton", -400, 17, 1.0, {"S": 1.4}),
    EventSpec("olympics-2025-run", "Olympics 2025 - Athletics (POC)", "olympics", "Running", -400, 17, 1.0, {}),
]


@dataclass
class SyntheticHistory:
    as_of: date
    skus: list[SkuRef]
    days: list[date]                         # D history days, the last one is as_of - 1
    demand: np.ndarray                       # (P, R, D) units sold (censored on stock-out days)
    unit_price: np.ndarray                   # (P, D)
    discount: np.ndarray                     # (P, D) percent
    event_idx: np.ndarray                    # (P, D) index into events or -1
    stockout: np.ndarray                     # (P, R, D) bool
    missing: np.ndarray                      # (P, R, D) bool: the day is absent from the mart (injected defect)
    on_hand: np.ndarray                      # (P, R, S) end-of-day stock for the last S days
    incoming: np.ndarray                     # (P, R, S)
    position: list[list[dict]]               # [p][r] current on_hand / oldest_age / avg_age / last_receipt
    purchase_orders: list[dict]
    reference: list[dict]                    # per SKU cost / supplier / lead time / moq / case pack
    situations: dict[str, str] = field(default_factory=dict)   # product_id -> scripted situation (documentation + tests only)
    events: list[dict] = field(default_factory=list)


def _seed(key: str) -> int:
    return int(hashlib.md5(f"{SEED}:{key}".encode()).hexdigest()[:12], 16)


def _rng(key: str) -> np.random.Generator:
    return np.random.default_rng(_seed(key))


def build_events(as_of: date) -> list[dict]:
    out = []
    for e in EVENT_SPECS:
        start = as_of + timedelta(days=e.start_offset)
        out.append({"id": e.id, "name": e.name, "event_type": e.event_type, "sport": e.sport, "start_date": start,
                    "end_date": start + timedelta(days=e.length - 1), "popularity": e.popularity, "regions": e.regions,
                    "data_source": REFERENCE_SOURCE})
    return out


def _situations(skus: list[SkuRef]) -> dict[str, str]:
    result: dict[str, str] = {}
    counters: dict[tuple[str, str], int] = {}
    for s in sorted(skus, key=lambda x: x.sku):
        key = (s.sport, s.category)
        rule = _PINNED.get(key)
        if rule and counters.get(key, 0) < rule[1]:
            result[s.product_id] = rule[0]
            counters[key] = counters.get(key, 0) + 1
            continue
        h = _seed("sit:" + s.sku) % 100
        result[s.product_id] = AT_RISK if h < 5 else OVERSTOCK if h < 11 else SLOW if h < 16 else NORMAL
    return result


def _reference(sku: SkuRef, situation: str) -> dict:
    r = _rng("ref:" + sku.sku)
    supplier, lead = _SUPPLIERS[_seed("sup:" + sku.sport) % len(_SUPPLIERS)]
    margin = float(r.uniform(0.30, 0.52))
    cost = round(sku.price * (1 - margin), 2)
    moq = int(r.choice([6, 12, 24, 24, 48])) if sku.price < 5000 else int(r.choice([2, 4, 6]))
    return {"product_id": sku.product_id, "cost": cost, "supplier": supplier, "lead_time_days": int(lead + r.integers(-2, 3)),
            "moq": moq, "case_pack": 1 if sku.price >= 5000 else 6}


def _daily_factors(as_of: date, days: list[date], sport: str, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    amp, peak = _SPORT_SEASON.get(sport, (0.15, 1))
    amp *= float(rng.uniform(0.7, 1.3))
    doy = np.array([d.timetuple().tm_yday for d in days], dtype=float)
    peak_doy = (peak - 1) * 30.4 + 15
    season = 1.0 + amp * np.cos(2 * np.pi * (doy - peak_doy) / 365.25)
    weekend = float(rng.uniform(1.15, 1.45))
    dow = np.array([weekend if d.weekday() >= 5 else (1.05 if d.weekday() == 4 else 1.0) for d in days])
    return season, dow


def _event_factor(spec_dates: list[dict], events: list[dict], days: list[date], sport: str, category: str, pop_weight: bool = True
                  ) -> tuple[np.ndarray, np.ndarray]:
    """Multiplicative event effect (with a smooth ramp) and the index of the event active each day. Only PAST events appear here."""
    D = len(days)
    factor = np.ones(D)
    idx = np.full(D, -1, dtype=int)
    cat = _EVENT_CATEGORY.get(category, 0.8)
    for k, ev in enumerate(events):
        if ev["sport"] != sport or ev["start_date"] > days[-1]:
            continue
        base = _EVENT_TYPE_UPLIFT.get(ev["event_type"], 1.5)
        if ev["event_type"] == "marathon" and category not in ("Shoes", "Accessories", "Jersey", "Wearables"):
            continue
        peak = 1.0 + (base - 1.0) * cat * (ev["popularity"] ** 0.6)
        length = (ev["end_date"] - ev["start_date"]).days + 1
        for t, d in enumerate(days):
            if ev["start_date"] <= d <= ev["end_date"]:
                pos = (d - ev["start_date"]).days
                ramp = min(1.0, 0.55 + 0.45 * pos / max(1, min(7, length - 1)))
                factor[t] *= 1.0 + (peak - 1.0) * ramp
                idx[t] = k
    return factor, idx


def generate(skus: list[SkuRef], as_of: date, *, missing_rate: float = 0.002) -> SyntheticHistory:
    D, S, R = HISTORY_DAYS, SNAPSHOT_DAYS, len(REGION_CODES)
    days = [as_of - timedelta(days=D - t) for t in range(D)]   # last = as_of - 1
    events = build_events(as_of)
    situations = _situations(skus)
    P = len(skus)
    demand = np.zeros((P, R, D), dtype=np.int32)
    unit_price = np.zeros((P, D))
    discount = np.zeros((P, D))
    event_idx = np.full((P, D), -1, dtype=int)
    stockout = np.zeros((P, R, D), dtype=bool)
    on_hand = np.zeros((P, R, S), dtype=np.int32)
    incoming = np.zeros((P, R, S), dtype=np.int32)
    position: list[list[dict]] = []
    pos_orders: list[dict] = []
    reference = [_reference(s, situations[s.product_id]) for s in skus]
    sitewide = [(-330, 10, 20.0), (-150, 10, 20.0)]

    for p, sku in enumerate(skus):
        rng = _rng("sku:" + sku.sku)
        sit = situations[sku.product_id]
        pop = rng.choice(["fast", "medium", "slow"], p=[0.25, 0.5, 0.25])
        base = {"fast": 14.0, "medium": 5.0, "slow": 1.2}[str(pop)] * float(rng.lognormal(0, 0.25))
        base *= _SPORT_VOLUME.get(sku.sport, 0.6) * (0.35 if sku.price > 10000 else 0.7 if sku.price > 5000 else 1.0)
        if sit == AT_RISK:
            base = max(base, 8.0 if sku.price < 8000 else 3.5)
        elif sit == OVERSTOCK:
            base = max(1.5, min(base, 5.0))
        elif sit == SLOW:
            base = 0.5 * float(rng.uniform(0.7, 1.3))
        growth = float(rng.normal(0.10, 0.14))
        if sit == OVERSTOCK:
            growth = -0.30
        season, dow = _daily_factors(as_of, days, sku.sport, rng)
        trend = np.exp(growth * (np.arange(D) - D) / 365.0)
        ev_factor, ev_idx = _event_factor([], events, days, sku.sport, sku.category)
        event_idx[p] = ev_idx

        # promotions: own windows + sitewide festival windows (discount percent per day)
        disc = np.zeros(D)
        for _ in range(int(rng.integers(4, 7))):
            start = int(rng.integers(20, D - 30))
            length = int(rng.integers(7, 15))
            disc[start:start + length] = float(rng.choice([10, 20, 30, 40, 50], p=[0.2, 0.3, 0.3, 0.15, 0.05]))
        for off, length, pct in sitewide:
            start = D + off
            disc[start:start + length] = np.maximum(disc[start:start + length], pct)
        eps = _ELASTICITY.get(sku.category, 1.6) * float(rng.uniform(0.9, 1.1))
        promo_mult = (1.0 - disc / 100.0) ** (-eps)
        dip = np.ones(D)
        for t in range(1, D):
            if disc[t - 1] > 0 and disc[t] == 0:
                dip[t:t + 7] *= 1.0 - 0.12 * min(1.0, promo_mult[t - 1] - 1.0)
        discount[p] = disc
        unit_price[p] = np.round(sku.price * (1.0 - disc / 100.0), 2)

        share = _BASE_SHARE * np.array(_SPORT_REGION_TILT.get(sku.sport, [1, 1, 1, 1, 1])) * rng.dirichlet(np.full(R, 40.0)) * R
        share = share / share.sum()
        region_event = np.ones((R, D))
        for k, ev in enumerate(events):
            if ev["sport"] == sku.sport and ev["regions"]:
                mask = ev_idx == k
                for r_i, code in enumerate(REGION_CODES):
                    region_event[r_i, mask] = ev["regions"].get(code, 1.0)

        lam_total = base * trend * season * dow * ev_factor * promo_mult * dip
        lead = reference[p]["lead_time_days"]
        moq = reference[p]["moq"]
        for r_i in range(R):
            lam = lam_total * share[r_i] * region_event[r_i]
            k_nb = 6.0
            draws = rng.poisson(rng.gamma(k_nb, lam / k_nb + 1e-12))
            po_rng = _rng(f"po:{sku.sku}:{r_i}")
            res = _simulate_stock(draws, lead, moq, sit, po_rng, as_of, D, S)
            demand[p, r_i] = res["sold"]
            stockout[p, r_i] = res["stockout"]
            on_hand[p, r_i] = res["on_hand"][-S:]
            incoming[p, r_i] = res["incoming"][-S:]
            pos_orders.extend({"product_id": sku.product_id, "region": REGION_CODES[r_i], "qty": q, "ordered_on": o, "eta": e}
                              for (o, e, q) in res["open_orders"])
            if r_i == 0:
                position.append([])
            position[p].append(res["position"])
    # injected data defects: a few days are missing from the mart (the DQ check must find them)
    miss_rng = _rng("missing")
    missing = miss_rng.random((P, R, D)) < missing_rate
    return SyntheticHistory(as_of, skus, days, demand, unit_price, discount, event_idx, stockout, missing, on_hand, incoming, position,
                            pos_orders, reference, situations, events)


def _simulate_stock(draws: np.ndarray, lead: int, moq: int, situation: str, rng: np.random.Generator, as_of: date, D: int, S: int) -> dict:
    """FIFO stock simulation with a periodic-review (s, S) policy driven by a planner's naive 28-day moving average, so organic
    stock-outs occur. Scripted situations: AT_RISK write-down + supplier disruption, OVERSTOCK old oversized receipt."""
    ma0 = max(0.05, float(draws[:28].mean()))
    batches: list[list[int]] = [[-60, int(math.ceil(ma0 * 50))]]       # [receipt_day_index, qty]
    pending: list[tuple[int, int, int]] = []                           # (ordered_idx, eta_idx, qty)
    sold = np.zeros(D, dtype=np.int32)
    so = np.zeros(D, dtype=bool)
    oh = np.zeros(D, dtype=np.int32)
    inc = np.zeros(D, dtype=np.int32)
    writedown_day = D - int(rng.integers(6, 26)) if situation == AT_RISK else -1
    overstock_day = D - int(rng.integers(110, 230)) if situation == OVERSTOCK else -1
    sigma_guess = 0.0
    for t in range(D):
        arrived = [x for x in pending if x[1] == t]
        for x in arrived:
            batches.append([t, x[2]])
        pending = [x for x in pending if x[1] != t]
        if t == overstock_day:
            batches.append([t, int(math.ceil(max(float(draws[max(0, t - 28):t].mean()), 0.5) * rng.uniform(130, 230)))])
        stock = sum(b[1] for b in batches)
        if t == writedown_day and stock > 0:
            recent = float(draws[max(0, t - 28):t].mean()) if t else ma0
            target = max(3, int(max(recent, 0.05) * ((D - t) + rng.uniform(3, 14))))   # leaves 3-14 days of cover at as_of
            if target < stock:                                         # stock correction (damage / shrinkage / mis-count)
                scale = target / stock
                for b in batches:
                    b[1] = int(b[1] * scale)
                batches = [b for b in batches if b[1] > 0]
                stock = sum(b[1] for b in batches)
        want = int(draws[t])
        take = min(want, stock)
        if want > stock:
            so[t] = True
        sold[t] = take
        left = take
        while left > 0 and batches:                                    # FIFO
            use = min(left, batches[0][1])
            batches[0][1] -= use
            left -= use
            if batches[0][1] == 0:
                batches.pop(0)
        oh[t] = sum(b[1] for b in batches)
        inc[t] = sum(x[2] for x in pending)
        disrupted = situation == AT_RISK and t >= writedown_day >= 0
        if t % 7 == 0 and not disrupted and t < D - 1:
            ma = max(0.02, float(sold[max(0, t - 27):t + 1].mean()))
            sigma_guess = float(sold[max(0, t - 27):t + 1].std())
            ss = 1.65 * sigma_guess * math.sqrt(max(lead, 1))
            reorder_point = ma * lead + ss
            position = oh[t] + inc[t]
            if position <= reorder_point:
                qty = int(math.ceil(max(moq, ma * (lead + 7) + ss - position) / moq) * moq)
                pending.append((t, t + lead, qty))
    if situation == AT_RISK and rng.random() < 0.5 and D > 5:           # a late order placed too late to prevent the stock-out
        pending.append((D - 3, D - 3 + int(2 * lead), int(max(moq, math.ceil(ma0 * 40 / moq) * moq))))
        inc[-1] = sum(x[2] for x in pending)
    ages = [(D - b[0], b[1]) for b in batches]
    total = sum(q for _, q in ages)
    position = {"on_hand": int(total), "oldest_age_days": int(max((a for a, q in ages if q > 0), default=0)),
                "avg_age_days": round(sum(a * q for a, q in ages) / total, 1) if total else 0.0,
                "last_receipt": (as_of - timedelta(days=min((a for a, q in ages if q > 0), default=0)) if total else None)}
    open_orders = [(as_of - timedelta(days=D - o), as_of + timedelta(days=e - D), q) for (o, e, q) in pending if e >= D]
    return {"sold": sold, "stockout": so, "on_hand": oh, "incoming": inc, "position": position, "open_orders": open_orders}
