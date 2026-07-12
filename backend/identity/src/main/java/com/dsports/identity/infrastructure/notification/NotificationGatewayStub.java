package com.dsports.identity.infrastructure.notification;

import com.dsports.identity.application.port.NotificationGateway;
import com.dsports.identity.domain.model.Email;

public class NotificationGatewayStub implements NotificationGateway {

    @Override
    public void sendVerificationEmail(Email to, String verificationLink) {
        throw new UnsupportedOperationException(
                "Notification not implemented. Verification email would be sent to " + to.value());
    }

    @Override
    public void sendWelcomeEmail(Email to, String displayName) {
        throw new UnsupportedOperationException(
                "Notification not implemented. Welcome email would be sent to " + to.value()
                        + " for " + displayName);
    }
}
