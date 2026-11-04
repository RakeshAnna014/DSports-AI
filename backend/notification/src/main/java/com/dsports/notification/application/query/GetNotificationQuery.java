package com.dsports.notification.application.query;

import java.util.UUID;

public record GetNotificationQuery(UUID notificationId, UUID userId, boolean isAdmin) {}
