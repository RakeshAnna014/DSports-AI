"""READ tools about the signed-in customer's account. Identity is the forwarded bearer token; no tool takes a customer id.

Every result is a WHITELIST built field by field: addresses, phone numbers, e-mail addresses, user ids and payment
instrument details are never copied, so they cannot reach the model or the customer-visible payload by accident.
"""
from __future__ import annotations

import asyncio
import datetime as dt
import re
import uuid
from typing import Any

from pydantic import Field, model_validator

from app.guard.untrusted import sanitize_untrusted
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

_ORDER_NUMBER = re.compile(r"^(ORD-\d{8}-)?\d{1,6}$", re.IGNORECASE)
MAX_ORDER_DETAILS = 8


def parse_time(value: str | None) -> dt.datetime | None:
    if not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=dt.timezone.utc)


class OrderRefArgs(StrictArgs):
    order_id: uuid.UUID | None = None
    order_number: str | None = Field(None, max_length=24, description="Full order number (ORD-YYYYMMDD-NNNNNN) or its last digits")
    latest: bool = False

    @model_validator(mode="after")
    def _exactly_one(self) -> "OrderRefArgs":
        given = sum([self.order_id is not None, self.order_number is not None, self.latest])
        if given != 1:
            raise ValueError("give exactly one of order_id, order_number, latest")
        if self.order_number is not None and not _ORDER_NUMBER.match(self.order_number):
            raise ValueError("order_number format")
        return self


class OptionalOrderArgs(StrictArgs):
    order_id: uuid.UUID | None = None


class OrdersArgs(StrictArgs):
    since: dt.date | None = None
    until: dt.date | None = None
    status: str | None = Field(None, max_length=30)
    limit: int = Field(10, ge=1, le=20)
    include_items: bool = True


class EligibilityArgs(StrictArgs):
    order_id: uuid.UUID
    product_id: uuid.UUID


class NoArgs(StrictArgs):
    pass


# ---------------------------------------------------------------------------------------------------- helpers

async def _order_rows(ctx: ToolContext) -> list[dict]:
    rows = await ctx.dsports.get("/api/v1/orders", token=ctx.token) or []
    return sorted(rows, key=lambda r: r.get("placedAt") or "", reverse=True)


async def resolve_order(ctx: ToolContext, a: OrderRefArgs) -> dict:
    """The customer's own order summary row. Another customer's order is simply not in this list (and a direct id is
    re-checked by DSports, which answers 403/404)."""
    rows = await _order_rows(ctx)
    if a.latest:
        if not rows:
            raise ToolError("NOT_FOUND", "You have no orders yet")
        return rows[0]
    if a.order_id is not None:
        match = next((r for r in rows if r["id"] == str(a.order_id)), None)
        if match is None:
            # Not in my list: let DSports decide (403/404). Never return data for an order that is not mine.
            await ctx.dsports.get(f"/api/v1/orders/{a.order_id}", token=ctx.token)
            raise ToolError("NOT_FOUND", "I could not find that order on your account")
        return match
    wanted = a.order_number.upper()  # type: ignore[union-attr]
    exact = [r for r in rows if r.get("orderNumber", "").upper() == wanted]
    suffix = [r for r in rows if r.get("orderNumber", "").upper().endswith(wanted.split("-")[-1].zfill(6))] if not exact else exact
    if len(suffix) == 1:
        return suffix[0]
    raise ToolError("NOT_FOUND", "I could not find that order on your account")


def _order_summary(row: dict, items: list[dict] | None = None) -> dict[str, Any]:
    out = {"order_id": row["id"], "order_number": row.get("orderNumber"), "status": row.get("status"),
           "placed_at": row.get("placedAt"), "total": row.get("grandTotal"), "currency": row.get("currency", "INR"),
           "total_items": row.get("totalItems")}
    if items is not None:
        out["items"] = items
    return out


def _items(detail: dict) -> list[dict]:
    return [{"product_id": i["productId"], "name": i["productName"], "quantity": i["quantity"], "unit_price": i["unitPrice"],
             "line_total": i.get("lineTotal")} for i in detail.get("items", [])]


async def _detail(ctx: ToolContext, order_id: str) -> dict:
    return await ctx.dsports.get(f"/api/v1/orders/{order_id}", token=ctx.token)


# ---------------------------------------------------------------------------------------------------- tools

async def get_customer_profile(ctx: ToolContext, a: NoArgs):
    p = await ctx.dsports.get("/api/customers/me", token=ctx.token)
    return {"first_name": (p or {}).get("firstName")}   # only what a greeting needs


async def get_cart(ctx: ToolContext, a: NoArgs):
    cart = await ctx.dsports.get("/api/v1/cart", token=ctx.token, allow_404=True)
    if not cart or not cart.get("items"):
        return {"items": [], "total_items": 0, "total_amount": 0, "currency": "INR", "empty": True}
    return {"items": [{"product_id": i["productId"], "name": i["productName"], "quantity": i["quantity"],
                       "unit_price": i["unitPrice"], "line_total": i["lineTotal"]} for i in cart["items"]],
            "total_items": cart["totalItems"], "total_amount": cart["totalAmount"], "currency": "INR", "empty": False}


async def get_orders(ctx: ToolContext, a: OrdersArgs):
    rows = await _order_rows(ctx)
    since = dt.datetime.combine(a.since, dt.time.min, dt.timezone.utc) if a.since else None
    until = dt.datetime.combine(a.until, dt.time.max, dt.timezone.utc) if a.until else None
    picked = []
    for r in rows:
        placed = parse_time(r.get("placedAt"))
        if placed and ((since and placed < since) or (until and placed > until)):
            continue
        if a.status and (r.get("status") or "").upper() != a.status.upper():
            continue
        picked.append(r)
    total = len(picked)
    picked = picked[: a.limit]
    details: list[dict | None] = [None] * len(picked)
    if a.include_items:
        fetched = await asyncio.gather(*(_detail(ctx, r["id"]) for r in picked[:MAX_ORDER_DETAILS]))
        details[: len(fetched)] = fetched
    return {"orders": [_order_summary(r, _items(d) if d else None) for r, d in zip(picked, details)],
            "matching_orders": total, "shown": len(picked)}


async def get_order(ctx: ToolContext, a: OrderRefArgs):
    row = await resolve_order(ctx, a)
    d = await _detail(ctx, row["id"])
    return {**_order_summary(d), "items": _items(d), "subtotal": d.get("subtotal"), "shipping_charge": d.get("shippingCharge"),
            "tax": d.get("taxAmount"), "discount": d.get("discountAmount"), "delivery_city": (d.get("shippingAddress") or {}).get("city")}


async def track_order(ctx: ToolContext, a: OrderRefArgs):
    row = await resolve_order(ctx, a)
    found = await ctx.dsports.get(f"/api/v1/shipments/by-order/{row['id']}", token=ctx.token, allow_404=True)
    base = {"order_id": row["id"], "order_number": row.get("orderNumber"), "order_status": row.get("status")}
    if not found:
        return {**base, "shipment": None, "message": "No shipment has been created for this order yet."}
    s = found["shipment"]
    return {**base, "shipment": {
        "shipment_number": s.get("shipmentNumber"), "tracking_number": s.get("trackingNumber"), "courier": s.get("courierProvider"),
        "status": s.get("status"), "estimated_delivery": s.get("estimatedDeliveryDate"), "shipped_at": s.get("shippedAt"),
        "delivered_at": s.get("deliveredAt"), "delivery_city": s.get("city"),
        "events": [{"status": e.get("status"), "time": e.get("eventTime"), "location": e.get("location"),
                    "description": e.get("description")} for e in (found.get("events") or [])[:5]]}}


async def _payments(ctx: ToolContext, order_id: uuid.UUID | None) -> list[dict]:
    rows = await ctx.dsports.get("/api/v1/payments/history", token=ctx.token) or []
    if order_id is not None:
        rows = [r for r in rows if r.get("orderId") == str(order_id)]
    return sorted(rows, key=lambda r: r.get("createdAt") or "", reverse=True)


async def get_payment_status(ctx: ToolContext, a: OptionalOrderArgs):
    rows = (await _payments(ctx, a.order_id))[:5]
    return {"payments": [{"payment_id": r["id"], "reference": r.get("paymentReference"), "order_id": r.get("orderId"),
                          "amount": r.get("amount"), "currency": r.get("currency", "INR"), "method": r.get("paymentMethod"),
                          "status": r.get("status"), "paid_at": r.get("paidAt")} for r in rows], "count": len(rows)}


async def get_refund_status(ctx: ToolContext, a: OptionalOrderArgs):
    payments = [p for p in await _payments(ctx, a.order_id) if p.get("status") in ("SUCCESS", "PARTIALLY_REFUNDED", "REFUNDED")][:5]

    async def one(p: dict) -> dict:
        refunds, detail = await asyncio.gather(ctx.dsports.get(f"/api/v1/payments/{p['id']}/refunds", token=ctx.token),
                                               ctx.dsports.get(f"/api/v1/payments/{p['id']}", token=ctx.token))
        return {"payment_id": p["id"], "order_id": p.get("orderId"), "payment_status": (detail or p).get("status"),
                "paid_amount": p.get("amount"), "refunded_amount": (detail or {}).get("refundedAmount", 0.0),
                "currency": p.get("currency", "INR"),
                "refunds": [{"refund_id": r["id"], "amount": r["amount"], "status": r["status"],
                             "reason": sanitize_untrusted(r.get("reason") or "", 200)[0],
                             "requested_at": r.get("createdAt"), "completed_at": r.get("completedAt")} for r in (refunds or [])]}

    results = await asyncio.gather(*(one(p) for p in payments))
    return {"payments": results, "refund_count": sum(len(r["refunds"]) for r in results)}


async def check_return_eligibility(ctx: ToolContext, a: EligibilityArgs):
    e = await ctx.dsports.get(f"/api/v1/orders/{a.order_id}/returns/eligibility", token=ctx.token, params={"productId": str(a.product_id)})
    return {k: e.get(v) for k, v in (
        ("eligible", "eligible"), ("reason", "reason"), ("message", "message"), ("order_id", "orderId"), ("product_id", "productId"),
        ("product_name", "productName"), ("ordered_quantity", "orderedQuantity"), ("already_requested_quantity", "alreadyRequestedQuantity"),
        ("max_returnable_quantity", "maxReturnableQuantity"), ("unit_price", "unitPrice"), ("currency", "currency"),
        ("delivered_at", "deliveredAt"), ("return_deadline", "returnDeadline"))}


def tools() -> list[Tool]:
    return [
        Tool("get_customer_profile", "The signed-in customer's first name (nothing else).", Kind.READ, NoArgs, get_customer_profile),
        Tool("get_cart", "The signed-in customer's current cart.", Kind.READ, NoArgs, get_cart),
        Tool("get_orders", "The signed-in customer's orders, optionally within a date range or status.", Kind.READ, OrdersArgs, get_orders),
        Tool("get_order", "One of the customer's orders: items and totals (identified by id, number or 'latest').", Kind.READ, OrderRefArgs, get_order),
        Tool("track_order", "Shipment status and tracking events for one of the customer's orders.", Kind.READ, OrderRefArgs, track_order),
        Tool("get_payment_status", "Payment status for the customer's orders.", Kind.READ, OptionalOrderArgs, get_payment_status),
        Tool("get_refund_status", "Refund requests and their status for the customer's payments.", Kind.READ, OptionalOrderArgs, get_refund_status),
        Tool("check_return_eligibility", "Whether a product of the customer's order can be returned (decided by DSports).", Kind.READ,
             EligibilityArgs, check_return_eligibility),
    ]
