-- Management feedback: two more ticket categories (order problems that are not delivery/payment, and website/technical problems),
-- plus indexes for the staff dashboard filters (created date, category/status, priority).
ALTER TABLE support_cases DROP CONSTRAINT ck_support_category;
ALTER TABLE support_cases ADD CONSTRAINT ck_support_category CHECK (category IN ('MISSING_DELIVERY', 'DELAYED_DELIVERY', 'DAMAGED_PRODUCT',
    'WRONG_PRODUCT', 'PARTIAL_DELIVERY', 'PAYMENT_ISSUE', 'DUPLICATE_PAYMENT', 'REFUND_ISSUE', 'RETURN_ISSUE', 'EXCHANGE_REQUEST',
    'PRODUCT_COMPLAINT', 'ORDER_ISSUE', 'TECHNICAL_ISSUE', 'GENERAL'));

CREATE INDEX ix_support_cases_created ON support_cases (created_at DESC);
CREATE INDEX ix_support_cases_category_status ON support_cases (category, status);
CREATE INDEX ix_support_cases_priority_status ON support_cases (priority, status);
