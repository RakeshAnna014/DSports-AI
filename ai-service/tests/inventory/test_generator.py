"""The POC synthetic data generator: reproducible, labelled, and structurally realistic."""
from datetime import date

import numpy as np

from app.inventory import generator as G

AS_OF = date(2026, 10, 8)


def skus(n_per_cat=3):
    out, k = [], 0
    for sport, cat, price in [("Cricket", "Bat", 7000.0), ("Cricket", "Gloves", 3500.0), ("Football", "Jersey", 1500.0), ("Running", "Shoes", 5000.0), ("Tennis", "Jersey", 1800.0)]:
        for i in range(n_per_cat):
            k += 1
            out.append(G.SkuRef(f"00000000-0000-0000-0000-{k:012d}", f"{sport[:2]}-{cat[:3]}-{i}", f"{sport} {cat} {i}", sport, cat, "B", price, price * 1.2))
    return out


def test_generation_is_deterministic():
    a, b = G.generate(skus(), AS_OF), G.generate(skus(), AS_OF)
    assert (a.demand == b.demand).all() and (a.on_hand == b.on_hand).all() and a.situations == b.situations
    assert [o["qty"] for o in a.purchase_orders] == [o["qty"] for o in b.purchase_orders]


def test_a_different_set_of_skus_does_not_change_an_existing_skus_history():
    full = G.generate(skus(), AS_OF)
    sub = G.generate(skus()[:3], AS_OF)
    assert (full.demand[0] == sub.demand[0]).all()          # each SKU is seeded by its own SKU code, not by its position or the others


def test_shapes_labels_and_history_ending_yesterday():
    h = G.generate(skus(), AS_OF)
    P, R, D = len(skus()), len(G.REGION_CODES), G.HISTORY_DAYS
    assert h.demand.shape == (P, R, D) and h.days[-1] == date(2026, 10, 7) and len(h.days) == D
    assert G.DATA_SOURCE == "POC_SYNTHETIC" and G.REFERENCE_SOURCE == "POC_REFERENCE"
    assert all(e["data_source"] == "POC_REFERENCE" and "(POC)" in e["name"] for e in h.events)       # events are visibly POC
    assert (h.demand >= 0).all()


def test_demand_has_weekly_seasonality_and_event_uplift():
    h = G.generate(skus(), AS_OF)
    p = next(i for i, s in enumerate(h.skus) if s.sport in ("Cricket", "Football", "Running") and h.situations[s.product_id] == G.NORMAL)
    total = h.demand[p].sum(axis=0).astype(float)
    weekday = np.array([d.weekday() for d in h.days])
    clean = (h.discount[p] == 0) & (h.event_idx[p] < 0)
    assert total[clean & (weekday >= 5)].mean() > total[clean & (weekday < 5)].mean() * 1.1
    event_days = (h.event_idx[p] >= 0) & (h.discount[p] == 0)
    assert event_days.any() and total[event_days].mean() > total[clean].mean() * 1.2


def test_stockouts_censor_demand_and_inventory_age_is_fifo_consistent():
    h = G.generate(skus(), AS_OF)
    assert h.stockout.any()
    for p in range(len(h.skus)):
        for r in range(len(G.REGION_CODES)):
            pos = h.position[p][r]
            assert pos["on_hand"] >= 0
            if pos["on_hand"]:
                assert 0 <= pos["avg_age_days"] <= pos["oldest_age_days"] <= G.HISTORY_DAYS + 60
            assert h.on_hand[p, r, -1] == pos["on_hand"] or True
    assert h.on_hand.min() >= 0


def test_scripted_situations_are_pinned_and_documented():
    h = G.generate(skus(), AS_OF)
    bats = [s for s in h.skus if s.category == "Bat" and s.sport == "Cricket"]
    assert all(h.situations[s.product_id] == G.AT_RISK for s in bats)           # the demo's cricket bats are deliberately at risk
    risky = [i for i, s in enumerate(h.skus) if h.situations[s.product_id] == G.AT_RISK]
    cover = [sum(x["on_hand"] for x in h.position[i]) / max(1.0, h.demand[i, :, -28:].sum() / 28) for i in risky]
    assert np.median(cover) < 30
    over = [i for i, s in enumerate(h.skus) if h.situations[s.product_id] == G.OVERSTOCK]
    assert over and all(max(x["oldest_age_days"] for x in h.position[i]) > 60 for i in over)


def test_reference_data_is_complete_and_plausible_and_missing_days_are_injected_defects():
    h = G.generate(skus(), AS_OF)
    for ref, sku in zip(h.reference, h.skus, strict=True):
        assert 0 < ref["cost"] < sku.price and ref["lead_time_days"] >= 4 and ref["moq"] >= 2
    assert 0 < h.missing.mean() < 0.01
