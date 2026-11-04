# Payment Problems Procedure (failed, duplicate or pending payments)

> SAMPLE DSports document, written for the demo knowledge base. It is a reference/demo procedure, not an official DSports
> policy, and it contains no refund-time commitment.

## What the payment statuses mean

A payment moves through CREATED, PENDING, PROCESSING and AUTHORIZED. SUCCESS means DSports captured the money. FAILED and
CANCELLED mean it was not captured. PARTIALLY_REFUNDED and REFUNDED show money that has been returned. A payment that stays
PROCESSING means the payment provider's final answer is not known yet.

## Money was deducted but the order failed

DSports can only see its own payment records, not your bank's. Check the payment status for the order. If no payment with the
status SUCCESS exists, DSports did not capture the money. A debit that your bank still shows may be a temporary hold from the
failed attempt, which the bank releases; DSports cannot see or release it. If a payment shows SUCCESS but the order is not
confirmed, that is a discrepancy: raise a support case so that a person can reconcile them.

## You were charged twice

Check how many payments show SUCCESS for the order. If there are two or more for the same order, raise a support case: a person
reviews it and decides on the refund through the normal refund process. If only one shows SUCCESS and the other attempt shows
FAILED or CANCELLED, DSports captured the money once; the other debit may be a hold.

## Payment pending

A PENDING or PROCESSING payment has no final result yet. Wait and check again. An unpaid order is held for a limited time and
then cancelled automatically (see the FAQ); if you were charged and the order was cancelled, raise a support case.

## What the assistant will and will not do

The assistant reads your order and payment records and explains them. It never concludes that you were charged because you say
so, it cannot capture, reverse or refund a payment, and it can raise a support case for a person to review after you confirm.
