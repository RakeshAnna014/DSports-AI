package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.UpdatePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class UpdatePriceUseCase {

    private final PriceRepository priceRepository;

    public UpdatePriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(UpdatePriceCommand command) {
        return Mono.defer(() -> {
            var newMrp = Money.from(command.mrp());
            var newSellingPrice = Money.from(command.sellingPrice());
            var newEffectiveDate = effectiveDate(command.effectiveFrom(), command.effectiveTo());

            return priceRepository.findById(PriceId.fromUUID(command.priceId()))
                    .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                            "Price not found: " + command.priceId())))
                    .flatMap(price -> {
                        price.updatePrice(newMrp, newSellingPrice, newEffectiveDate);
                        return priceRepository.save(price)
                                .thenReturn(PriceResultMapper.toResult(price));
                    });
        });
    }

    private static EffectiveDate effectiveDate(Instant effectiveFrom, Instant effectiveTo) {
        if (effectiveFrom != null) {
            return EffectiveDate.from(effectiveFrom, effectiveTo);
        }
        return EffectiveDate.immediate();
    }
}
