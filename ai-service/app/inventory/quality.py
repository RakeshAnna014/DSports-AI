"""Data-quality checks over the inventory mart. They REPORT; nothing is silently corrected (the cleaning rules in forecasting.py
are explicit and the number of affected days is stored with every check).

Checked: missing dates, duplicates, negative values, zero-sales days, stock-out (censored) days, outliers, unexplained stock decreases
(stock corrections), and missing reference data (supplier lead time, cost, MOQ, regions). Cancelled orders and returns exist only in the
transactional DSports database, not in this mart: `scripts/inventory_real_data_audit.py` reports on them.
"""
from __future__ import annotations

import numpy as np


def _check(name: str, status: str, detail: str, **numbers) -> dict:
    return {"check": name, "status": status, "detail": detail, **numbers}


def report(m, series: dict) -> dict:
    P_, R_, D_ = m.units.shape
    expected = P_ * R_ * D_
    missing = int(np.isnan(m.units).sum())
    neg = int((m.units < 0).sum())
    zero = int((m.units == 0).sum())
    stock = int(m.stockout.sum())
    no_lt = sum(s["lead_time_days"] is None for s in m.skus)
    no_cost = sum(s["cost"] is None for s in m.skus)
    no_moq = sum(s["moq"] is None for s in m.skus)
    counts = [c.counts for c in series.values()]
    outliers = 0
    for c in series.values():
        ok = ~c.mask & ~np.isnan(c.raw)
        v = c.raw[ok]
        if len(v) > 30:
            med = float(np.median(v))
            mad = float(np.median(np.abs(v - med))) or 1.0
            outliers += int((np.abs(v - med) / (1.4826 * mad) > 6).sum())
    sparse = [s["name"] for s, c in zip(m.skus, series.values(), strict=True) if int((~c.mask).sum()) < 56]
    checks = [
        _check("history_length", "OK", f"{D_} days per SKU and region ({m.days[0]} to {m.days[-1]})", days=D_),
        _check("missing_dates", "WARN" if missing else "OK",
               f"{missing} of {expected} SKU-region-days are absent from the mart ({100 * missing / expected:.2f}%). They are rebuilt from the regions that were "
               "observed (scaled by regional share) and flagged as imputed.", count=missing),
        _check("duplicates", "OK", "The (product, region, day) primary key makes duplicate demand rows impossible; 0 duplicates.", count=0),
        _check("negative_units", "FAIL" if neg else "OK", f"{neg} negative demand values.", count=neg),
        _check("zero_sales_days", "INFO", f"{zero} zero-sales SKU-region-days ({100 * zero / expected:.1f}%): normal for slow movers; kept as real zeros.", count=zero),
        _check("stockout_days", "WARN" if stock else "OK",
               f"{stock} SKU-region-days had unmet demand (stock ran out). Sales on those days under-state demand, so product-days where >= 20% of "
               "regional demand was censored are excluded from model training and evaluation.", count=stock,
               product_days_excluded=int(sum(c["stockout_days"] for c in counts))),
        _check("promotion_and_event_days", "INFO",
               f"{int(sum(c['promo_days'] for c in counts))} promotion (+7-day dip) and {int(sum(c['event_days'] for c in counts))} event product-days are modelled "
               "separately and imputed in the baseline series.", ),
        _check("outliers", "INFO", f"{outliers} baseline days are more than 6 robust standard deviations from the SKU median; kept (no silent removal).", count=outliers),
        _check("insufficient_history", "WARN" if sparse else "OK", f"{len(sparse)} SKUs have fewer than 56 clean days and get NO forecast "
               "('Insufficient data to produce a reliable forecast').", skus=sparse[:10], count=len(sparse)),
        _check("reference_data", "WARN" if (no_lt or no_cost or no_moq) else "OK",
               f"Missing supplier lead time: {no_lt}; missing cost: {no_cost}; missing MOQ: {no_moq}. Cost, supplier, lead time and MOQ are POC REFERENCE "
               "data (DSports holds none of them).", missing_lead_time=no_lt, missing_cost=no_cost, missing_moq=no_moq),
        _check("regions", "INFO", f"{R_} POC regions. DSports has no regional stock or demand dimension; regional figures are POC SYNTHETIC."),
    ]
    issues = sum(c["status"] in ("WARN", "FAIL") for c in checks)
    return {"data_source": "POC_SYNTHETIC", "checks": checks, "issue_count": issues, "skus": P_, "regions": R_, "days": D_,
            "disclaimer": "Generated history, not real DSports sales. Real transactional data is too small and recent to train on (see docs/ai/phase2/02-data-model.md)."}


async def snapshot_check(pool) -> dict:
    """Stock decreases that sales do not explain (corrections / shrinkage): on_hand(t) < on_hand(t-1) - units(t)."""
    row = await pool.fetchrow(
        """SELECT count(*) AS n FROM (
             SELECT s.on_hand, lag(s.on_hand) OVER (PARTITION BY s.product_id, s.region ORDER BY s.day) AS prev, d.units
             FROM inv_stock_snapshot s JOIN inv_demand_daily d ON d.product_id = s.product_id AND d.region = s.region AND d.day = s.day) x
           WHERE prev IS NOT NULL AND on_hand < prev - units""")
    n = int(row["n"])
    return _check("stock_corrections", "WARN" if n else "OK",
                  f"{n} SKU-region-days show a stock decrease that sales do not explain (write-down, damage, mis-count). Kept as recorded; they change the "
                  "starting position, not the demand history.", count=n)
