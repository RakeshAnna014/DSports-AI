package com.dsports.notification.application.query;

import java.util.UUID;

public record GetNotificationHistoryQuery(
    UUID userId,
    boolean isAdmin,
    int page,
    int size
) {}
