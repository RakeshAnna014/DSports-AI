package com.dsports.notification.application.command;

import java.util.UUID;

public record RetryNotificationCommand(UUID notificationId) {}
