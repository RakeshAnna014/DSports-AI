package com.dsports.identity.application.command;

import com.dsports.identity.domain.model.AddressType;
import com.dsports.identity.domain.model.UserId;

public record CreateAddressCommand(
    UserId userId,
    String line1,
    String line2,
    String city,
    String state,
    String country,
    String postalCode,
    AddressType type
) {}
