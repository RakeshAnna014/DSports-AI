package com.dsports.order.application.checkout.command;

import java.util.UUID;

public record CreateCheckoutCommand(UUID customerId) {}
