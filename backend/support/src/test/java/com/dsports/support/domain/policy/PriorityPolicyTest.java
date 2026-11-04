package com.dsports.support.domain.policy;

import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CasePriority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityPolicyTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("10000");

    @Test
    void base_priority_follows_the_category() {
        assertThat(PriorityPolicy.evaluate(CaseCategory.GENERAL, false, null, 0, 0, THRESHOLD).priority()).isEqualTo(CasePriority.LOW);
        assertThat(PriorityPolicy.evaluate(CaseCategory.MISSING_DELIVERY, false, null, 0, 0, THRESHOLD).priority()).isEqualTo(CasePriority.MEDIUM);
    }

    @Test
    void a_duplicate_payment_is_high_only_when_the_backend_itself_counted_more_than_one_payment() {
        assertThat(PriorityPolicy.evaluate(CaseCategory.DUPLICATE_PAYMENT, false, null, 0, 1, THRESHOLD).priority()).isEqualTo(CasePriority.MEDIUM);
        var confirmed = PriorityPolicy.evaluate(CaseCategory.DUPLICATE_PAYMENT, false, null, 0, 2, THRESHOLD);
        assertThat(confirmed.priority()).isEqualTo(CasePriority.HIGH);
        assertThat(confirmed.reasons()).anyMatch(r -> r.contains("2 successful payments"));
    }

    @Test
    void a_high_value_order_and_a_repeat_contact_each_raise_the_priority_one_level_and_say_so() {
        var r = PriorityPolicy.evaluate(CaseCategory.MISSING_DELIVERY, false, new BigDecimal("12000"), 1, 1, THRESHOLD);
        assertThat(r.priority()).isEqualTo(CasePriority.URGENT);
        assertThat(r.reasons()).hasSize(3).anyMatch(s -> s.contains("high-value")).anyMatch(s -> s.contains("Repeat contact"));
    }

    @Test
    void below_the_threshold_nothing_is_added() {
        assertThat(PriorityPolicy.evaluate(CaseCategory.MISSING_DELIVERY, false, new BigDecimal("9999.99"), 0, 1, THRESHOLD).priority()).isEqualTo(CasePriority.MEDIUM);
    }

    @Test
    void only_the_explicit_safety_flag_makes_a_case_urgent() {
        assertThat(PriorityPolicy.evaluate(CaseCategory.GENERAL, true, null, 0, 0, THRESHOLD).priority()).isEqualTo(CasePriority.URGENT);
        assertThat(PriorityPolicy.evaluate(CaseCategory.DAMAGED_PRODUCT, false, null, 0, 0, THRESHOLD).priority()).isEqualTo(CasePriority.MEDIUM);
    }

    @Test
    void priority_never_exceeds_urgent() {
        assertThat(CasePriority.URGENT.bump()).isEqualTo(CasePriority.URGENT);
    }
}
