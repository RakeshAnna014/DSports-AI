package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.model.Currency;
import com.dsports.pricing.domain.model.PriceStatus;
import com.dsports.pricing.domain.model.ProductId;
import reactor.core.publisher.Flux;

public class GetPricesUseCase {

    private final PriceRepository priceRepository;

    public GetPricesUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Flux<PriceResult> execute() {
        return priceRepository.findAll()
                .map(PriceResultMapper::toResult);
    }

    public Flux<PriceResult> execute(ProductId productId) {
        return priceRepository.findByProductId(productId)
                .map(PriceResultMapper::toResult);
    }
}
