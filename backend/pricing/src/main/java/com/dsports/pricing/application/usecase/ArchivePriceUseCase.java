package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.ArchivePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.PriceId;
import reactor.core.publisher.Mono;

public class ArchivePriceUseCase {

    private final PriceRepository priceRepository;

    public ArchivePriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(ArchivePriceCommand command) {
        return priceRepository.findById(PriceId.fromUUID(command.priceId()))
                .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                        "Price not found: " + command.priceId())))
                .flatMap(price -> {
                    price.archive();
                    return priceRepository.save(price)
                            .thenReturn(PriceResultMapper.toResult(price));
                });
    }
}
