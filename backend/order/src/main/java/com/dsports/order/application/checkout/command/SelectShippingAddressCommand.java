package com.dsports.order.application.checkout.command;

import java.util.UUID;

public record SelectShippingAddressCommand(UUID checkoutId, UUID customerId, UUID addressId) {}
