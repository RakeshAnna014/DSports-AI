package com.dsports.identity.application.port;

import com.dsports.identity.domain.model.Email;

public interface NotificationGateway {
    void sendVerificationEmail(Email to, String verificationLink);
    void sendWelcomeEmail(Email to, String displayName);
}
