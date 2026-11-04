package com.dsports.support.domain.model;

/**
 * One piece of evidence attached to a case.
 *
 * @param kind     what it is about: ORDER, SHIPMENT, PAYMENT, RETURN, REFUND, PRODUCT, POLICY, CUSTOMER_STATEMENT, STAFF_FINDING
 * @param ref      the identifier the fact came from (order number, tracking number, payment reference ...), if any
 * @param summary  one factual sentence
 * @param source   BACKEND_VERIFIED (read by this backend from its own records at creation) | AI_INVESTIGATION (submitted by the assistant,
 *                 which read it from the customer's own records but is not re-checked here) | CUSTOMER (the customer's statement)
 *                 | STAFF (added by support staff)
 */
public record EvidenceItem(String kind, String ref, String summary, String source) {

    public static final String BACKEND_VERIFIED = "BACKEND_VERIFIED";
    public static final String AI_INVESTIGATION = "AI_INVESTIGATION";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";

    public EvidenceItem {
        kind = clip(kind, 30);
        ref = clip(ref, 100);
        summary = clip(summary, 400);
        source = clip(source, 30);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        var v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
