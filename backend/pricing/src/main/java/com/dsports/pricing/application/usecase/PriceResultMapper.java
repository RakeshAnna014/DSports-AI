package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.result.PriceSummaryResult;
import com.dsports.pricing.domain.model.Price;

public final class PriceResultMapper {

    private PriceResultMapper() {}

    public static PriceResult toResult(Price price) {
        return new PriceResult(
                price.getId().value(),
                price.getProductId().value(),
                price.getMrp().value(),
                price.getSellingPrice().value(),
                price.getCurrency().code(),
                price.getEffectiveDate().effectiveFrom(),
                price.getEffectiveDate().effectiveTo(),
                price.getStatus().name(),
                price.getVersion(),
                price.getCreatedAt(),
                price.getUpdatedAt()
        );
    }

    public static PriceSummaryResult toSummary(Price price) {
        return new PriceSummaryResult(
                price.getId().value(),
                price.getProductId().value(),
                price.getMrp().value(),
                price.getSellingPrice().value(),
                price.getCurrency().code(),
                price.getStatus().name()
        );
    }
}
