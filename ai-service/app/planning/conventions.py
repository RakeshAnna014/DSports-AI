"""The ACTUAL-SALES CONVENTION, in one place, as pure functions. Every report, aggregate and test uses these, so a number means one thing everywhere.

Source: the DSports order lines (the admin sales feed). One feed row is one order line with the order status, cancellation, approved returned quantity and the order discount
already allocated to the line by value.

Definitions (units are order-line quantities; money is the order currency, before tax and shipping, which the feed does not carry per line):

  RECOGNISED  An order is a sale once payment is captured: status CONFIRMED, PROCESSING, SHIPPED or DELIVERED (and REFUNDED, which was one). CREATED and PENDING_PAYMENT lines
              are NOT sales (payment not captured / failed / abandoned) and are counted only as `excluded`, so a reconciliation shows them.
  gross       the quantity of a line whose order was recognised, or was cancelled after being placed (CANCELLED): what customers asked us to ship.
  cancelled   the quantity of CANCELLED lines.
  returned    for a non-cancelled line: the approved returned quantity, or the whole quantity if the order is REFUNDED (never both: the larger of the two, capped at the quantity).
  net         gross - cancelled - returned. A refund or return is therefore subtracted exactly once and never appears as an additional sale.
  gross_revenue   line value (unit price x quantity) of the gross units, before discount.
  discount        the order discount allocated to the line, pro rata by line value.
  net_revenue     (line value - allocated discount) x net / quantity: revenue of the units that stayed sold.

WEEK: the sale belongs to the business-timezone week of `placed_at` (the feed has no separate payment time). A later cancellation or approved return RESTATES the original
week; it never creates a negative entry in a later week. Recent weeks can therefore still change, which the aggregate shows through its `aggregated_at` and the week's closed flag.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Mapping

RECOGNISED = frozenset({"CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED", "REFUNDED"})
CANCELLED = "CANCELLED"
NOT_A_SALE = frozenset({"CREATED", "PENDING_PAYMENT"})
KNOWN_STATUSES = RECOGNISED | {CANCELLED} | NOT_A_SALE
_CENT = Decimal("0.01")


def _d(v: Any) -> Decimal:
    return v if isinstance(v, Decimal) else Decimal(str(v if v is not None else 0))


@dataclass(frozen=True)
class LineSale:
    """The recognised effect of one order line. All fields are zero for a line that is not a sale."""
    status: str
    counted: bool                 # False for CREATED / PENDING_PAYMENT / an unknown status
    gross_units: int
    cancelled_units: int
    returned_units: int
    net_units: int
    gross_revenue: Decimal
    discount: Decimal
    net_revenue: Decimal


def recognise(line: Mapping[str, Any]) -> LineSale:
    """Apply the convention to one feed row (keys: orderStatus/order_status, quantity, lineTotal/line_total, allocatedDiscount/allocated_discount, approvedReturnQuantity/returned_quantity)."""
    g = lambda *keys: next((line[k] for k in keys if k in line and line[k] is not None), None)  # noqa: E731
    status = str(g("order_status", "orderStatus") or "").upper()
    qty = int(g("quantity") or 0)
    if qty < 0:
        raise ValueError("a line quantity cannot be negative")
    if status not in KNOWN_STATUSES or status in NOT_A_SALE or qty == 0:
        return LineSale(status, False, 0, 0, 0, 0, Decimal(0), Decimal(0), Decimal(0))
    total = _d(g("line_total", "lineTotal")).quantize(_CENT, ROUND_HALF_UP)
    discount = min(_d(g("allocated_discount", "allocatedDiscount")), total).quantize(_CENT, ROUND_HALF_UP)
    if status == CANCELLED:
        return LineSale(status, True, qty, qty, 0, 0, total, discount, Decimal(0))
    approved = max(0, int(g("returned_quantity", "approvedReturnQuantity") or 0))
    returned = min(qty, max(approved, qty if status == "REFUNDED" else 0))
    net = qty - returned
    net_rev = ((total - discount) * net / qty).quantize(_CENT, ROUND_HALF_UP)
    return LineSale(status, True, qty, 0, returned, net, total, discount, net_rev)
