"""WRITE tools: create_return and reorder_product.

Neither can run through ToolRegistry.call without the confirmation service having authorised that exact call. Each has a
`prepare` step that works from LIVE data and refuses impossible actions up front; the same step is run again right before
execution (state may have changed since the customer was asked). The decision itself - is this return allowed, is this
product purchasable at this price - always belongs to DSports, never to us.
"""
from __future__ import annotations

import asyncio
import uuid

from pydantic import Field

from app.tools.account_tools import MAX_ORDER_DETAILS, _detail, _order_rows
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, Proposal, StrictArgs, Tool, ToolContext


def inr(value: float) -> str:
    return f"₹{value:,.2f}"


class CreateReturnArgs(StrictArgs):
    order_id: uuid.UUID
    product_id: uuid.UUID
    quantity: int = Field(1, ge=1, le=100)
    reason: str = Field(min_length=3, max_length=500)


class ReorderArgs(StrictArgs):
    product_id: uuid.UUID
    order_id: uuid.UUID | None = None
    quantity: int | None = Field(None, ge=1, le=20)


# ---------------------------------------------------------------------------------------------------- returns

async def _prepare_return(ctx: ToolContext, a: CreateReturnArgs) -> Proposal:
    e = await ctx.dsports.get(f"/api/v1/orders/{a.order_id}/returns/eligibility", token=ctx.token, params={"productId": str(a.product_id)})
    if not e.get("eligible"):
        raise ToolError("NOT_ELIGIBLE", e.get("message") or "This product cannot be returned")
    if a.quantity > e["maxReturnableQuantity"]:
        raise ToolError("QUANTITY_EXCEEDS", f"You can return at most {e['maxReturnableQuantity']} unit(s) of this product")
    rows = await _order_rows(ctx)
    number = next((r.get("orderNumber") for r in rows if r["id"] == str(a.order_id)), None)
    reason = " ".join(a.reason.split())
    summary = (f"Return {a.quantity} x {e['productName']} from order {number or a.order_id}"
               f" (refund is handled separately). Reason: {reason}")
    return Proposal(summary, {"order_id": str(a.order_id), "product_id": str(a.product_id), "quantity": a.quantity, "reason": reason},
                    {"product_name": e["productName"], "order_number": number, "unit_price": e.get("unitPrice"),
                     "return_deadline": e.get("returnDeadline"), "max_returnable_quantity": e["maxReturnableQuantity"]})


async def _create_return(ctx: ToolContext, a: CreateReturnArgs):
    r = await ctx.dsports.post("/api/v1/returns", token=ctx.token, idempotency_key=ctx.idempotency_key,
                               json={"orderId": str(a.order_id), "productId": str(a.product_id), "quantity": a.quantity,
                                     "reason": " ".join(a.reason.split())})
    return {"return_id": r["id"], "status": r["status"], "product_name": r.get("productName"), "quantity": r["quantity"],
            "order_id": r["orderId"], "created_at": r.get("createdAt")}


# ---------------------------------------------------------------------------------------------------- reorder

async def _find_line(ctx: ToolContext, product_id: uuid.UUID, order_id: uuid.UUID | None) -> tuple[dict, dict]:
    """(order summary row, the order line) for the product, from the customer's own orders (most recent first)."""
    if order_id is not None:
        detail = await _detail(ctx, str(order_id))  # DSports enforces ownership (403/404)
        line = next((i for i in detail.get("items", []) if i["productId"] == str(product_id)), None)
        if line is None:
            raise ToolError("NOT_IN_ORDER", "That product is not part of that order")
        return {"id": detail["id"], "orderNumber": detail.get("orderNumber")}, line
    rows = (await _order_rows(ctx))[:MAX_ORDER_DETAILS]
    details = await asyncio.gather(*(_detail(ctx, r["id"]) for r in rows))
    for row, d in zip(rows, details):
        line = next((i for i in d.get("items", []) if i["productId"] == str(product_id)), None)
        if line:
            return row, line
    raise ToolError("NOT_FOUND", "I could not find that product in your recent orders")


async def _prepare_reorder(ctx: ToolContext, a: ReorderArgs) -> Proposal:
    row, line = await _find_line(ctx, a.product_id, a.order_id)
    pid = str(a.product_id)
    live = await ctx.services.catalog.live([pid], ctx.token)
    product = live.get(pid)
    if product is None or product.price is None:
        raise ToolError("UNAVAILABLE", "That product is no longer available to buy")
    qty = a.quantity or int(line["quantity"])
    stock = product.stock
    if stock is None or not stock.in_stock:
        raise ToolError("OUT_OF_STOCK", f"{product.name} is out of stock right now")
    if qty > stock.available_quantity:
        raise ToolError("OUT_OF_STOCK", f"Only {stock.available_quantity} of {product.name} available right now")
    price = product.price.selling_price
    old = float(line["unitPrice"])
    note = f" (it was {inr(old)} when you ordered it on {row.get('orderNumber')})" if abs(old - price) > 0.005 else ""
    return Proposal(f"Add {qty} x {product.name} to your cart at the current price of {inr(price)} each, {inr(price * qty)} in total{note}",
                    {"product_id": pid, "order_id": row["id"], "quantity": qty},
                    {"product_name": product.name, "current_unit_price": price, "previous_unit_price": old, "price_changed": abs(old - price) > 0.005,
                     "currency": product.price.currency, "available_quantity": stock.available_quantity, "order_number": row.get("orderNumber")})


async def _reorder(ctx: ToolContext, a: ReorderArgs):
    qty = a.quantity or 1
    cart = await ctx.dsports.post("/api/v1/cart/items", token=ctx.token, idempotency_key=ctx.idempotency_key,
                                  json={"productId": str(a.product_id), "quantity": qty})
    line = next((i for i in cart.get("items", []) if i["productId"] == str(a.product_id)), None)
    return {"added": {"product_id": str(a.product_id), "name": (line or {}).get("productName"), "quantity": qty,
                      "unit_price": (line or {}).get("unitPrice")},
            "cart": {"total_items": cart.get("totalItems"), "total_amount": cart.get("totalAmount"), "currency": "INR"},
            "next_step": "Review your cart and check out when you are ready. Nothing has been ordered or paid."}


async def _prepare_return_tool(ctx, a): return await _prepare_return(ctx, a)
async def _prepare_reorder_tool(ctx, a): return await _prepare_reorder(ctx, a)


def tools() -> list[Tool]:
    return [
        Tool("create_return", "Create a return request for a delivered order's product. Needs the customer's confirmation.", Kind.WRITE,
             CreateReturnArgs, _create_return, prepare=_prepare_return_tool),
        Tool("reorder_product", "Add a previously ordered product to the cart at today's price. Needs the customer's confirmation; never places an order.",
             Kind.WRITE, ReorderArgs, _reorder, prepare=_prepare_reorder_tool),
    ]
