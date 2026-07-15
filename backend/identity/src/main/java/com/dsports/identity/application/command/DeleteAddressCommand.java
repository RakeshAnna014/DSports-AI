package com.dsports.identity.application.command;

import com.dsports.identity.domain.model.AddressId;
import com.dsports.identity.domain.model.UserId;

public record DeleteAddressCommand(
    UserId userId,
    AddressId addressId
) {}
