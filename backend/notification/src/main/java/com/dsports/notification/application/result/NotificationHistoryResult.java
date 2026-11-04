package com.dsports.notification.application.result;

import java.util.List;

public record NotificationHistoryResult(
    List<NotificationResult> notifications,
    long totalCount,
    int page,
    int size
) {}
