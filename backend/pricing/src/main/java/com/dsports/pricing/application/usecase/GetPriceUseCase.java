package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.PriceId;
import reactor.core.publisher.Mono;

public class GetPriceUseCase {

    private final PriceRepository priceRepository;

    public GetPriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(PriceId priceId) {
        return priceRepository.findById(priceId)
                .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                        "Price not found: " + priceId.value())))
                .map(PriceResultMapper::toResult);
    }
}
