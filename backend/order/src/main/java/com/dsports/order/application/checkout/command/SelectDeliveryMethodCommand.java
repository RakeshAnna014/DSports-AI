package com.dsports.order.application.checkout.command;

import java.util.UUID;

public record SelectDeliveryMethodCommand(UUID checkoutId, UUID customerId, String deliveryMethodCode) {}
