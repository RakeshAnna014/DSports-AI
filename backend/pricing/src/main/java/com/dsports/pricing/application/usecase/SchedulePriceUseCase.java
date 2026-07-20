package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.SchedulePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.PriceId;
import reactor.core.publisher.Mono;

public class SchedulePriceUseCase {

    private final PriceRepository priceRepository;

    public SchedulePriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(SchedulePriceCommand command) {
        return Mono.defer(() -> {
            var priceId = PriceId.fromUUID(command.priceId());
            return priceRepository.findById(priceId)
                    .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                            "Price not found: " + command.priceId())))
                    .flatMap(price -> {
                        price.schedule(command.scheduledFrom());
                        return priceRepository.save(price)
                                .thenReturn(PriceResultMapper.toResult(price));
                    });
        });
    }
}
