package com.dsports.identity.interfaces.dto;

import com.dsports.identity.application.result.AddressListResult;

import java.util.List;

public record AddressListResponse(
    List<AddressResponse> addresses
) {
    public static AddressListResponse from(AddressListResult result) {
        var list = result.addresses().stream()
                .map(AddressResponse::from)
                .toList();
        return new AddressListResponse(list);
    }
}
