package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.CreatePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class CreatePriceUseCase {

    private final PriceRepository priceRepository;

    public CreatePriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(CreatePriceCommand command) {
        return Mono.defer(() -> {
            var productId = ProductId.fromUUID(command.productId());
            var mrp = Money.from(command.mrp());
            var sellingPrice = Money.from(command.sellingPrice());
            var currency = Currency.from(command.currency());
            var effectiveDate = effectiveDate(command.effectiveFrom(), command.effectiveTo());

            return priceRepository.existsByProductIdAndCurrencyAndStatus(
                            productId, currency, PriceStatus.ACTIVE)
                    .flatMap(exists -> {
                        if (exists) {
                            return Mono.error(new PricingDomainException(
                                    PricingErrorCode.OVERLAPPING_ACTIVE_PRICE,
                                    "An active price already exists for product "
                                            + command.productId() + " in currency " + command.currency()));
                        }
                        var price = Price.create(productId, mrp, sellingPrice, currency, effectiveDate);
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
