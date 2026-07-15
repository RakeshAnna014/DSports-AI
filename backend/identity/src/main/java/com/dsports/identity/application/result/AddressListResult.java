package com.dsports.identity.application.result;

import java.util.List;

public record AddressListResult(
    List<AddressResult> addresses
) {
    public static AddressListResult from(List<AddressResult> addresses) {
        return new AddressListResult(addresses);
    }
}
