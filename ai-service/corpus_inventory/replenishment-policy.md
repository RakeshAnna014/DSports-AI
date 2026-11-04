# Replenishment Policy (SAMPLE internal document)

This is a SAMPLE policy written for the DSports AI proof of concept; it is not DSports' real procurement policy.

## Service level and safety stock

- The planning target is a 95% cycle service level.
- Safety stock covers the uncertainty of demand over the supplier lead time. It is calculated from the forecast error measured in backtests, never set by hand.
- The reorder point is the forecast demand over the lead time plus the safety stock.

## Order quantity

- Stock is reviewed weekly (a 7 day review period).
- The recommended quantity brings the inventory position (on hand plus open purchase orders) up to the forecast demand over the lead time and review period plus the safety stock.
- Quantities are rounded up to the supplier's case pack and raised to the minimum order quantity.

## Priority of high-risk inventory

- HIGH risk: the stock is projected to run out before a new order could arrive, or the chance of a stock-out before replenishment is at least 50%. Replenish immediately and ask the supplier to expedite any open order that lands after the projected stock-out date.
- MEDIUM risk: projected cover is less than the lead time plus 7 days, or the chance of a stock-out is at least 20%. Replenish within the week.
- LOW risk: no action beyond the normal weekly review.

## Approval

- Purchase orders are raised by the buying team. A purchase order above INR 500,000 needs approval from the Head of Procurement.
- The AI assistant only recommends. It never creates, changes or approves a purchase order.
