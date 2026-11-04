package com.dsports.shipment.application.command;

import com.dsports.shipment.domain.model.CourierProvider;
import java.util.UUID;

public record AssignCourierCommand(
    UUID shipmentId,
    CourierProvider courierProvider
) {}
