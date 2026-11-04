"""Reading the DSports sales feed and turning it into planning rows.

`SalesFeedClient` pages through GET /api/v1/admin/sales/lines with the ADMIN's own bearer token (the AI service has no identity of its own). `prepare_line` applies the sales convention,
the business calendar and the region mapping to one feed row and returns exactly what is stored in `pln_sales_line`; `PlanningIngest` runs the whole job idempotently:
the same feed read twice changes nothing, a changed order updates its lines, and the weekly aggregates are recomputed ONLY for the weeks the changed lines touch.
"""
from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, AsyncIterator, Mapping

from app.planning import regions
from app.planning.calendar import Calendar
from app.planning.conventions import KNOWN_STATUSES, NOT_A_SALE, recognise
from app.tools.dsports_client import DSportsClient, ToolError

log = logging.getLogger(__name__)
FEED_PATH = "/api/v1/admin/sales/lines"
PAGE_SIZE = 500


class IngestError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _ts(v: Any) -> datetime | None:
    if v is None:
        return None
    if isinstance(v, datetime):
        return v if v.tzinfo else v.replace(tzinfo=timezone.utc)
    return datetime.fromisoformat(str(v).replace("Z", "+00:00"))


def prepare_line(row: Mapping[str, Any], cal: Calendar, state_map: Mapping[str, str]) -> dict[str, Any]:
    """One feed row to one `pln_sales_line` record. Raises ValueError on a row that cannot be a sales line (the caller counts and reports it)."""
    for key in ("lineId", "orderId", "productId", "quantity", "orderStatus", "placedAt", "sourceUpdatedAt"):
        if row.get(key) in (None, ""):
            raise ValueError(f"the feed row has no {key}")
    placed = _ts(row["placedAt"])
    sale = recognise(row)
    region = regions.region_of(row.get("shipState"), state_map)
    return {
        "line_id": uuid.UUID(str(row["lineId"])), "order_id": uuid.UUID(str(row["orderId"])), "order_number": row.get("orderNumber"), "product_id": uuid.UUID(str(row["productId"])),
        "sku": row.get("sku"), "quantity": int(row["quantity"]), "order_status": str(row["orderStatus"]).upper(), "placed_at": placed, "cancelled_at": _ts(row.get("cancelledAt")),
        "currency": row.get("currency") or "INR", "unit_price": row.get("unitPrice"), "line_total": row.get("lineTotal"), "allocated_discount": row.get("allocatedDiscount"),
        "approved_return_qty": int(row.get("approvedReturnQuantity") or 0), "ship_city": row.get("shipCity"), "ship_state": row.get("shipState"),
        "location_id": regions.online_location(region), "region": region, "week_start": cal.week_of(placed), "counted": sale.counted,
        "gross_units": sale.gross_units, "cancelled_units": sale.cancelled_units, "returned_units": sale.returned_units, "net_units": sale.net_units,
        "gross_revenue": sale.gross_revenue, "discount": sale.discount, "net_revenue": sale.net_revenue, "source_updated_at": _ts(row["sourceUpdatedAt"]),
    }


class SalesFeedClient:
    def __init__(self, dsports: DSportsClient, page_size: int = PAGE_SIZE):
        self._d = dsports
        self._page = max(1, min(page_size, 1000))

    async def pages(self, token: str, *, after: tuple[datetime | None, str | None] = (None, None), placed_from: datetime | None = None,
                    placed_to: datetime | None = None) -> AsyncIterator[dict[str, Any]]:
        ts, line = after
        for _ in range(10_000):                                              # a hard stop: a feed that never ends is an error, not an infinite loop
            params: dict[str, Any] = {"limit": self._page}
            if ts:
                params["afterUpdatedAt"] = ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            if line:
                params["afterLineId"] = line
            if placed_from:
                params["placedFrom"] = placed_from.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            if placed_to:
                params["placedTo"] = placed_to.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            try:
                page = await self._d.get(FEED_PATH, token=token, params=params)
            except ToolError as exc:
                if exc.status == 403:
                    raise IngestError("FORBIDDEN", "The DSports sales feed needs an administrator") from None
                raise IngestError(exc.code, f"The DSports sales feed could not be read ({exc.message})") from None
            if not isinstance(page, dict) or not isinstance(page.get("items"), list):
                raise IngestError("BAD_FEED", "The DSports sales feed returned an unreadable page")
            yield page
            nxt = page.get("next") or {}
            if not page.get("hasMore"):
                return
            ts, line = _ts(nxt.get("updatedAt")), nxt.get("lineId")
            if not line:
                raise IngestError("BAD_FEED", "The DSports sales feed has more rows but no cursor")
        raise IngestError("BAD_FEED", "The DSports sales feed did not finish")


@dataclass
class IngestResult:
    run_id: str
    status: str
    lines_read: int = 0
    lines_changed: int = 0
    lines_unchanged: int = 0
    lines_excluded: int = 0
    weeks_touched: int = 0
    unknown_region_lines: int = 0
    unknown_status_lines: int = 0
    bad_rows: int = 0
    unknown_products: int = 0
    restated_lines: int = 0
    data_through: str | None = None
    error: str | None = None


class PlanningIngest:
    def __init__(self, repo, feed: SalesFeedClient, cal: Calendar, state_map: Mapping[str, str], restate_days: int = 45):
        self.repo, self.feed, self.cal, self.state_map, self.restate_days = repo, feed, cal, dict(state_map), restate_days

    async def run(self, actor_hash: str, token: str, *, mode: str = "INCREMENTAL") -> IngestResult:
        """INCREMENTAL reads what changed since the last run, then re-reads the last `restate_days` of orders (a return approved later does not touch the order, so a recent window is
        always re-read). BACKFILL reads everything. Either way the result is the same data: this is idempotent."""
        if mode not in ("INCREMENTAL", "BACKFILL"):
            raise IngestError("INVALID_MODE", "mode must be INCREMENTAL or BACKFILL")
        run = await self.repo.start_ingest(actor_hash, mode)
        if run is None:
            raise IngestError("INGEST_RUNNING", "An ingest is already running")
        res = IngestResult(run["id"], "RUNNING")
        try:
            cursor = (None, None) if mode == "BACKFILL" else await self.repo.ingest_cursor()
            touched: set[tuple[Any, ...]] = set()
            last_cursor = cursor
            async for page in self.feed.pages(token, after=cursor):
                await self._apply(page["items"], res, touched)
                nxt = page.get("next") or {}
                last_cursor = (_ts(nxt.get("updatedAt")), nxt.get("lineId"))
            if mode == "INCREMENTAL":
                since = datetime.now(timezone.utc) - timedelta(days=self.restate_days)
                async for page in self.feed.pages(token, placed_from=since):
                    await self._apply(page["items"], res, touched, restating=True)
            if touched:
                res.weeks_touched = await self.repo.reaggregate_live(touched)
            res.data_through = (await self.repo.mark_live_data_through(self.cal)).isoformat()      # the feed was read to its end: complete through the end of yesterday
            res.status = "SUCCEEDED"
            await self.repo.finish_ingest(run["id"], "SUCCEEDED", res, last_cursor, None)
        except IngestError as exc:
            res.status, res.error = "FAILED", f"{exc.code}: {exc.message}"
            await self.repo.finish_ingest(run["id"], "FAILED", res, None, res.error)          # the cursor does not move on a failure: nothing is lost, the next run repeats it
        except Exception as exc:  # noqa: BLE001
            log.exception("planning ingest failed")
            res.status, res.error = "FAILED", f"UNEXPECTED: {type(exc).__name__}"
            await self.repo.finish_ingest(run["id"], "FAILED", res, None, res.error)
        return res

    async def _apply(self, items: list[dict[str, Any]], res: IngestResult, touched: set, restating: bool = False) -> None:
        prepared = []
        for row in items:
            res.lines_read += 1
            try:
                p = prepare_line(row, self.cal, self.state_map)
            except (ValueError, KeyError):
                res.bad_rows += 1
                continue
            if not restating:                       # the restatement window re-reads lines already counted in the main pass: they are not counted twice
                if p["order_status"] in NOT_A_SALE:
                    res.lines_excluded += 1
                elif p["order_status"] not in KNOWN_STATUSES:
                    res.unknown_status_lines += 1
                    res.lines_excluded += 1
                if p["location_id"] == regions.online_location(regions.UNKNOWN_REGION) and p["counted"]:
                    res.unknown_region_lines += 1
            prepared.append(p)
        changed, unchanged, keys, unknown_products = await self.repo.upsert_lines(prepared)
        res.unknown_products += unknown_products
        if restating:
            res.restated_lines += changed
        res.lines_changed += changed if not restating else 0
        res.lines_unchanged += unchanged if not restating else 0
        touched |= keys
