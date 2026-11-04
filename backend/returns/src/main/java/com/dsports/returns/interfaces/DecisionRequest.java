package com.dsports.returns.interfaces;

import jakarta.validation.constraints.Size;

public record DecisionRequest(@Size(max = 500) String note) {
}
