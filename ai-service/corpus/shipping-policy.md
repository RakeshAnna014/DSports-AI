# DSports Shipping and Tracking

> SAMPLE DSports document, written for the demo knowledge base. It describes how DSports shipments behave; it is not
> manufacturer documentation and not a delivery guarantee.

## When an order ships

An order is shipped only after its payment has been captured. As soon as the payment succeeds, DSports creates a shipment
for the order automatically, using the delivery address saved on the order. If you do not pay, the order stays in
**PENDING_PAYMENT** and is cancelled automatically after the payment window, which is 30 minutes by default; the reserved
stock is then released.

## Shipment statuses

A shipment moves through these statuses in order:

1. **CREATED**: the shipment exists.
2. **READY_FOR_PICKUP**: the parcel is packed and waiting for the courier.
3. **PICKED_UP**: the courier has collected it.
4. **IN_TRANSIT**: it is on its way.
5. **OUT_FOR_DELIVERY**: it is with the delivery person for today's delivery.
6. **DELIVERED**: it has reached you. The order then becomes DELIVERED and the 7-day return window starts.

Sometimes a shipment shows **FAILED** (a delivery problem that the team can retry) or **RETURNED** (sent back to DSports).

## Tracking your order

Each shipment has a tracking number that starts with TRK. You can see the current status and the history of events for any
of your own orders. The assistant can look this up for you by order number, or for your latest order.

## Delivery time

DSports shows the estimated delivery date on the shipment when the courier provides one. When there is no estimate, the
assistant will say so and will not guess a date.

## Delivery charges

The delivery charge for your order is calculated and shown at checkout before you pay; it depends on the delivery method
you choose and your order. The final amount on your order is what you pay.
