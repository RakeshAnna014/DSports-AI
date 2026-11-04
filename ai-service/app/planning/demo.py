"""DEMONSTRATION store network and store-level history. NOT real business data and never presented as such.

DSports has no stores, no store-level sales and no store stock: it sells online from one warehouse. To exercise store-level planning anyway, this module builds a labelled network
(5 regions x 2 zones x 2 stores, one store opened recently, one planned) and SPLITS the Inventory AI's synthetic REGIONAL history (`inv_demand_daily`, itself POC_SYNTHETIC) into stores:

  * every region-week total is divided across the stores that were OPEN that week by a multinomial draw, so the stores of a region always add up to the region exactly;
  * the draw probabilities come from a store size factor and a seeded store x sport affinity (the generator's own made-up "taste"), so different stores genuinely differ and the
    regional-assortment analysis has something to find: that structure is an invention of this generator and is stated as such wherever it is shown;
  * everything is deterministic (fixed seed) and labelled `POC_SYNTHETIC` / `POC_REFERENCE`.
The same functions split current regional stock and open purchase orders across stores. Nothing here reads or writes the DSports commerce database.
"""
from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Sequence

import numpy as np

SEED = 20_261_015
ZONES_PER_REGION = 2
STORES_PER_ZONE = 2
REGION_NAMES = {"N": "North", "S": "South", "E": "East", "W": "West", "C": "Central"}
SPORT_TASTE_RANGE = (0.6, 1.6)
DEMO_NOTE = "DEMONSTRATION: a made-up store network with synthetic sales. Not real DSports stores or sales."


@dataclass(frozen=True)
class StoreSpec:
    id: str
    name: str
    region: str
    zone: str
    size_factor: float
    opened_on: date | None
    status: str                      # ACTIVE | PLANNED


def _rng(*key: object) -> np.random.Generator:
    digest = hashlib.blake2b("|".join(str(k) for k in (SEED, *key)).encode(), digest_size=8).digest()
    return np.random.default_rng(int.from_bytes(digest, "big"))


def store_layout(as_of: date) -> list[StoreSpec]:
    """The demonstration stores. One store (South, zone 2, store 2) opened six weeks before `as_of` (a NEW store: little history), one (East, zone 2, store 2) opens in three weeks (PLANNED)."""
    out: list[StoreSpec] = []
    for r, rname in REGION_NAMES.items():
        for z in range(1, ZONES_PER_REGION + 1):
            for s in range(1, STORES_PER_ZONE + 1):
                sid = f"{r}-Z{z}-S{s}"
                size = round(float(_rng("size", sid).uniform(0.7, 1.4)), 3)
                opened, status = None, "ACTIVE"
                if sid == "S-Z2-S2":
                    opened = as_of - timedelta(weeks=6)
                elif sid == "E-Z2-S2":
                    opened, status = as_of + timedelta(weeks=3), "PLANNED"
                out.append(StoreSpec(sid, f"{rname} Zone {z} Store {s}", r, f"{r}-Z{z}", size, opened, status))
    return out


def taste(store_id: str, sport: str) -> float:
    """How much more or less of a sport this store sells than its size alone implies. A property of the SYNTHETIC generator, not of the world."""
    lo, hi = SPORT_TASTE_RANGE
    return round(float(_rng("taste", store_id, sport).uniform(lo, hi)), 3)


def store_probs(stores: Sequence[StoreSpec], sport: str, week_start: date) -> np.ndarray:
    """Probability that a unit sold in the stores' region in `week_start` was sold in each store. Stores not yet open that week get 0. All-zero (no open store) returns zeros."""
    w = np.array([(s.size_factor * taste(s.id, sport)) if (s.opened_on is None or s.opened_on <= week_start) else 0.0 for s in stores], dtype=float)
    total = w.sum()
    return w / total if total > 0 else w


def split_units(total: int, probs: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Divide a region's weekly units across stores. The parts always add up to `total` (when any store is open)."""
    if total <= 0 or probs.sum() <= 0:
        return np.zeros(len(probs), dtype=int)
    return rng.multinomial(int(total), probs)


def allocate(total: int, probs: np.ndarray) -> np.ndarray:
    """Largest-remainder split of a stock quantity: deterministic, sums exactly to `total`."""
    if total <= 0 or probs.sum() <= 0:
        return np.zeros(len(probs), dtype=int)
    raw = probs * total
    base = np.floor(raw).astype(int)
    short = int(total - base.sum())
    if short > 0:
        order = np.argsort(-(raw - base), kind="stable")
        base[order[:short]] += 1
    return base


def week_sql(week_start_day: int) -> str:
    """SQL expression turning a `day` column into its week start for a given first weekday (0 = Monday)."""
    w = int(week_start_day)
    return f"(date_trunc('week', day - INTERVAL '{w} days') + INTERVAL '{w} days')::date"


def seasonal_note() -> str:
    return DEMO_NOTE + " Store differences come from a seeded store size and a seeded store x sport taste in the generator."


def expected_share_check(probs: np.ndarray) -> bool:
    return bool(math.isclose(float(probs.sum()), 1.0, abs_tol=1e-9)) or float(probs.sum()) == 0.0
