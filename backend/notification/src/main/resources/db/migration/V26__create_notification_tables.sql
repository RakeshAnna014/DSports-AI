CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    subject VARCHAR(200),
    message TEXT NOT NULL,
    template_name VARCHAR(100),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    reference_id VARCHAR(100),
    reference_type VARCHAR(50),
    failure_reason TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_notification_type_valid CHECK (type IN (
        'ORDER_PLACED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED', 'ORDER_CONFIRMED',
        'ORDER_SHIPPED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUND_COMPLETED'
    )),
    CONSTRAINT ck_notification_channel_valid CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notification_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RETRYING')),
    CONSTRAINT ck_notification_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT ck_notification_max_retries_positive CHECK (max_retries > 0)
);

CREATE TABLE notification_templates (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(30) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    subject_template VARCHAR(200),
    body_template TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_template_name_channel UNIQUE (name, channel),
    CONSTRAINT ck_template_type_valid CHECK (type IN (
        'ORDER_PLACED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED', 'ORDER_CONFIRMED',
        'ORDER_SHIPPED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUND_COMPLETED'
    )),
    CONSTRAINT ck_template_channel_valid CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP'))
);

CREATE INDEX ix_notifications_user_id ON notifications(user_id);
CREATE INDEX ix_notifications_status ON notifications(status);
CREATE INDEX ix_notifications_type ON notifications(type);
CREATE INDEX ix_notifications_created_at ON notifications(created_at);
CREATE INDEX ix_notifications_user_status ON notifications(user_id, status);
CREATE INDEX ix_notification_templates_active ON notification_templates(active);
