# DSports Return Policy

> SAMPLE DSports document, written for the demo knowledge base. It describes how the DSports returns feature behaves; it
> is not manufacturer documentation and not legal advice.

## Who can return what

You can ask to return a product from one of your own orders when the order has been **delivered**. A return is requested
for one product of one order at a time. Orders that are still waiting for payment, being processed, shipped or cancelled
cannot be returned, and a product that is not part of the order cannot be returned against it.

## The return window

The return window is **7 days from delivery**. The window starts when the order is marked delivered. After 7 days the
return can no longer be requested through DSports. The window is configured in the DSports system (`app.returns.window`,
default 7 days), and the return eligibility check always shows the exact deadline for your order.

## How many units can be returned

You can return all units of a product or only some of them. The most you can return is the quantity you ordered minus
the units you have already asked to return. For example, if you bought 3 pairs of socks and already asked to return 1,
you can still ask for 2 more. When a return request is **rejected**, its units become returnable again, because a
rejected request does not hold any quantity.

## How to request a return

1. Tell the assistant which order and product you want to return, or open the order and choose to return the product.
2. DSports checks eligibility: delivered order, product in the order, inside the 7-day window, remaining quantity.
3. You give a short reason (up to 500 characters) and confirm the return.

Each return request needs a unique request key, so pressing the button twice, or a network retry, never creates a second
request. The assistant always asks you to confirm before it creates a return.

## What happens to a return request

A new request has the status **REQUESTED**, which means it is waiting for a DSports team member to review it.
The team member then marks it **APPROVED** or **REJECTED**, and may add a note. An approved or rejected request cannot be
changed again.

## Returns and refunds are separate

Creating or approving a return does not by itself move any money. Refunds are handled by the refund process described in
the Refund Policy. If your return is approved, a refund is requested and approved separately; you can follow it under
your payments or by asking the assistant for your refund status.

## What the assistant will and will not do

The assistant can check whether a product is eligible, explain this policy, and create a return request after you confirm.
It cannot approve a return, change the 7-day window, or promise that a request will be approved.
