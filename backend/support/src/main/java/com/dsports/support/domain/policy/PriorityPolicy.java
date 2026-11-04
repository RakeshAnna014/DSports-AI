package com.dsports.support.domain.policy;

import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CasePriority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Explainable POC prioritisation. Every rule is listed here and every applied rule is recorded on the case as a reason, so a
 * staff member can see exactly why a case has its priority. These are demo rules, NOT an official DSports SLA or policy.
 *
 * <ol>
 *   <li>Base by category: GENERAL, EXCHANGE_REQUEST, PRODUCT_COMPLAINT, TECHNICAL_ISSUE = LOW; PAYMENT_ISSUE, DELAYED_DELIVERY,
 *       MISSING_DELIVERY, DAMAGED_PRODUCT, WRONG_PRODUCT, PARTIAL_DELIVERY, REFUND_ISSUE, RETURN_ISSUE, ORDER_ISSUE = MEDIUM;
 *       DUPLICATE_PAYMENT = MEDIUM, or HIGH when this backend itself counted more than one successful payment for the order.</li>
 *   <li>One level up when the order total reaches the configured high-value threshold.</li>
 *   <li>One level up when the customer already has another case about the same order (a repeat contact).</li>
 *   <li>URGENT when the case is flagged safety-related (an explicit flag; never inferred from tone or wording).</li>
 * </ol>
 * Inputs never include personal attributes, complaint frequency across orders, or any fraud signal.
 */
public final class PriorityPolicy {

    public record Result(CasePriority priority, List<String> reasons) {}

    private PriorityPolicy() {}

    public static Result evaluate(CaseCategory category, boolean safetyRelated, BigDecimal orderTotal, int otherCasesAboutTheSameOrder,
                                  int successfulPaymentsOnOrder, BigDecimal highValueThreshold) {
        var reasons = new ArrayList<String>();
        CasePriority priority = switch (category) {
            case GENERAL, EXCHANGE_REQUEST, PRODUCT_COMPLAINT, TECHNICAL_ISSUE -> CasePriority.LOW;
            case DUPLICATE_PAYMENT -> successfulPaymentsOnOrder > 1 ? CasePriority.HIGH : CasePriority.MEDIUM;
            default -> CasePriority.MEDIUM;
        };
        reasons.add("Base priority " + priority + " for category " + category);
        if (category == CaseCategory.DUPLICATE_PAYMENT && successfulPaymentsOnOrder > 1) {
            reasons.add("The backend counted " + successfulPaymentsOnOrder + " successful payments for this order");
        }
        if (orderTotal != null && highValueThreshold != null && orderTotal.compareTo(highValueThreshold) >= 0) {
            priority = priority.bump();
            reasons.add("Order total " + orderTotal.toPlainString() + " is at or above the high-value threshold " + highValueThreshold.toPlainString());
        }
        if (otherCasesAboutTheSameOrder > 0) {
            priority = priority.bump();
            reasons.add("Repeat contact: " + otherCasesAboutTheSameOrder + " other case(s) about the same order");
        }
        if (safetyRelated) {
            priority = CasePriority.URGENT;
            reasons.add("Flagged as safety-related");
        }
        return new Result(priority, List.copyOf(reasons));
    }
}
