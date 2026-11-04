package com.dsports.shipment.application.command;

import java.util.UUID;

/** Shipments are always created for a paid order; the delivery address is taken from the order, never the caller. */
public record CreateShipmentCommand(UUID orderId) {}
