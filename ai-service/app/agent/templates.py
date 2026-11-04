"""Deterministic answers built ONLY from tool data. Used for Tier-0 intents, whenever the model is unavailable, and as the
safe replacement when a model answer fails the grounding check."""
from __future__ import annotations

import datetime as dt
from typing import Any

NOT_AVAILABLE = "Not available in the product data"


def inr(value: float | None) -> str:
    return f"₹{value:,.2f}" if value is not None else NOT_AVAILABLE


def when(iso: str | None) -> str:
    if not iso:
        return "not available"
    try:
        return dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).strftime("%d %b %Y")
    except ValueError:
        return iso


def stock_text(card: dict[str, Any]) -> str:
    if "in_stock" not in card:
        return "stock not available"
    if not card["in_stock"]:
        return "out of stock"
    q = card.get("available_quantity")
    return f"in stock ({q} available)" if q is not None and q <= 5 else "in stock"


def weight_text(g: float | None) -> str:
    if g is None:
        return NOT_AVAILABLE
    return f"{g / 1000:g} kg" if g >= 1000 else f"{g:g} g"


def products_text(data: dict[str, Any], slots: dict[str, Any]) -> str:
    items = data["products"]
    if not items:
        return ("I couldn't find a matching product that is in stock right now. "
                "Try a different category, a higher budget, or ask me about a specific sport or brand.")
    head = "Here are the closest matches" if data.get("over_budget") else "Here are the best matches"
    brand = slots.get("brand")
    if brand and not any((p.get("brand") or "").lower() == str(brand).lower() for p in items):
        head = f"We have no {brand} product for that search. Here are the closest alternatives"   # never present other brands as the requested one
    if data.get("over_budget"):
        head += f", but nothing fits your budget of {inr(slots.get('max_price'))} - these are the nearest options"
    lines = [f"{head}:"]
    for i, p in enumerate(items, 1):
        extras = [p.get("attributes", {}).get("skillLevel"), p.get("attributes", {}).get("weightClass")]
        tail = ", ".join(x.replace("_", " ") for x in extras if x)
        lines.append(f"{i}. **{p['name']}** - {inr(p.get('price'))}, {stock_text(p)}" + (f" ({tail})" if tail else ""))
    if data.get("relaxed_filters"):
        lines.append(f"(I widened the search by ignoring: {', '.join(data['relaxed_filters'])}.)")
    return "\n".join(lines)


def comparison_text(table: dict[str, Any]) -> str:
    names = [p["name"] for p in table["products"]]
    lines = ["Comparison (all values from the product data):"]
    for row in table["rows"]:
        lines.append(f"- {row['attribute']}: " + " | ".join(f"{n}: {v}" for n, v in zip(names, row["values"])))
    return "\n".join(lines)


def order_line(o: dict[str, Any]) -> str:
    items = ", ".join(f"{i['quantity']} x {i['name']}" for i in o.get("items", [])) or f"{o.get('total_items', '?')} item(s)"
    return f"{o['order_number']} - {o['status']} - {inr(o.get('total'))} - placed {when(o.get('placed_at'))} - {items}"


def orders_text(data: dict[str, Any], label: str | None) -> str:
    if not data["orders"]:
        return f"You have no orders{' in ' + label if label else ''}."
    scope = f" ({label})" if label else ""
    lines = [f"Your orders{scope}:"] + [f"- {order_line(o)}" for o in data["orders"]]
    if data["matching_orders"] > data["shown"]:
        lines.append(f"Showing {data['shown']} of {data['matching_orders']}.")
    return "\n".join(lines)


def order_text(o: dict[str, Any]) -> str:
    lines = [f"Order {o['order_number']} is {o['status']} (placed {when(o.get('placed_at'))})."]
    lines += [f"- {i['quantity']} x {i['name']} at {inr(i['unit_price'])}" for i in o.get("items", [])]
    lines.append(f"Total: {inr(o.get('total'))}")
    return "\n".join(lines)


def tracking_text(t: dict[str, Any]) -> str:
    head = f"Order {t['order_number']} is {t['order_status']}."
    s = t.get("shipment")
    if not s:
        return f"{head} {t.get('message', 'No shipment yet.')}"
    lines = [f"{head} Shipment {s['shipment_number']} is {s['status']} (tracking number {s['tracking_number']}, courier {s['courier']})."]
    if s.get("estimated_delivery"):
        lines.append(f"Estimated delivery: {when(s['estimated_delivery'])}.")
    else:
        lines.append("There is no estimated delivery date for this shipment.")
    if s.get("delivered_at"):
        lines.append(f"Delivered on {when(s['delivered_at'])}.")
    if s.get("events"):
        lines.append("Latest events: " + "; ".join(f"{e['status']} ({when(e['time'])})" for e in s["events"][:3]))
    return " ".join(lines[:1]) + "\n" + "\n".join(lines[1:])


def payments_text(data: dict[str, Any]) -> str:
    if not data["payments"]:
        return "I couldn't find any payments for that."
    return "Your payments:\n" + "\n".join(
        f"- {p['reference']}: {p['status']}, {inr(p['amount'])} via {p['method']}" + (f", paid {when(p['paid_at'])}" if p.get("paid_at") else "")
        for p in data["payments"])


def refunds_text(data: dict[str, Any]) -> str:
    if not data["payments"]:
        return "I couldn't find a captured payment with refunds on your account."
    if data["refund_count"] == 0:
        return "There are no refund requests on your payments. If you want one, you can request it from your payment details."
    lines = ["Your refunds:"]
    for p in data["payments"]:
        for r in p["refunds"]:
            lines.append(f"- {inr(r['amount'])}: {r['status']} (requested {when(r['requested_at'])}"
                         + (f", completed {when(r['completed_at'])}" if r.get("completed_at") else "") + ")")
        if p["refunds"]:
            lines.append(f"  Payment status: {p['payment_status']}, refunded so far {inr(p['refunded_amount'])} of {inr(p['paid_amount'])}.")
    return "\n".join(lines)


def cart_text(c: dict[str, Any]) -> str:
    if c.get("empty"):
        return "Your cart is empty."
    lines = ["In your cart:"] + [f"- {i['quantity']} x {i['name']} at {inr(i['unit_price'])} = {inr(i['line_total'])}" for i in c["items"]]
    return "\n".join(lines + [f"Total: {inr(c['total_amount'])}"])


def eligibility_text(e: dict[str, Any]) -> str:
    if e["eligible"]:
        return (f"{e['product_name']} from this order can be returned: up to {e['max_returnable_quantity']} unit(s), "
                f"until {when(e.get('return_deadline'))}.")
    return f"{e.get('product_name') or 'That product'} cannot be returned: {e.get('message')}"


def _weight(card: dict[str, Any]) -> str | None:
    w = card.get("weight_g")
    return None if w is None else weight_text(w)


def product_detail_text(card: dict[str, Any], asked: list[str]) -> str:
    """Facts about one product, only from the DSports card. `asked` narrows the answer ("how heavy is it?"); empty means an overview."""
    name = card["name"]
    attrs = card.get("attributes") or {}
    lines: list[str] = []

    def have(key: str) -> bool:
        return not asked or key in asked

    if have("weight"):
        w = _weight(card)
        extra = f" ({str(attrs['weightClass']).replace('_', ' ')} weight class)" if attrs.get("weightClass") else ""
        lines.append(f"**{name}** weighs {w}{extra}." if w else f"The weight of **{name}** is {NOT_AVAILABLE.lower()}.")
    if have("material"):
        m = attrs.get("material")
        lines.append(f"**{name}** is made of {str(m).replace('_', ' ')}." if m else f"The material of **{name}** is {NOT_AVAILABLE.lower()}.")
    if have("price"):
        mrp = f" (list price {inr(card['mrp'])})" if card.get("mrp") and card.get("price") is not None and card["mrp"] > card["price"] else ""
        lines.append(f"**{name}** costs {inr(card.get('price'))}{mrp}.")
    if have("stock"):
        lines.append(f"**{name}** is {stock_text(card)}.")
    if have("dimensions"):
        lines.append(f"Dimensions of **{name}**: {card.get('dimensions') or NOT_AVAILABLE.lower()}.")
    if have("rating"):
        r = card.get("rating")
        lines.append(f"**{name}** is rated {r['average']:.1f}/5 from {r['count']} reviews." if r else f"**{name}** has no reviews yet.")
    if not asked:
        for key, label in (("skillLevel", "Skill level"), ("playFormat", "Play format"), ("ballType", "Ball type"), ("surface", "Surface"), ("ageGroup", "Age group")):
            if attrs.get(key):
                lines.append(f"{label}: {str(attrs[key]).replace('_', ' ')}.")
        if card.get("description"):
            lines.append(str(card["description"])[:300])
    return "\n".join(lines)


def reviews_text(name: str, reviews: dict[str, Any]) -> str:
    total = reviews.get("total_reviews") or 0
    items = reviews.get("reviews") or []
    if not total and not items:
        return f"**{name}** has no approved customer reviews yet."
    avg = reviews.get("average_rating")
    head = f"**{name}**: " + (f"{float(avg):.1f}/5 average from {total} review{'s' if total != 1 else ''}." if avg is not None else f"{total} reviews.")
    body = [f"- {i.get('rating')}/5" + (f" - {i['title']}" if i.get("title") else "") for i in items[:5]]
    return "\n".join([head, *body])
