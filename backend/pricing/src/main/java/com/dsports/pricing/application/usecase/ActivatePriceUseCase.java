package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.ActivatePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.PriceId;
import com.dsports.pricing.domain.model.PriceStatus;
import reactor.core.publisher.Mono;

public class ActivatePriceUseCase {

    private final PriceRepository priceRepository;

    public ActivatePriceUseCase(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Mono<PriceResult> execute(ActivatePriceCommand command) {
        return Mono.defer(() -> {
            var priceId = PriceId.fromUUID(command.priceId());
            return priceRepository.findById(priceId)
                    .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                            "Price not found: " + command.priceId())))
                    .flatMap(price -> {
                        price.activate();
                        return deactivateConflicting(price)
                                .then(priceRepository.save(price))
                                .thenReturn(PriceResultMapper.toResult(price));
                    });
        });
    }

    private Mono<Void> deactivateConflicting(com.dsports.pricing.domain.model.Price price) {
        if (price.getStatus() != PriceStatus.ACTIVE) {
            return Mono.empty();
        }
        return priceRepository.deactivateActivePrices(
                price.getProductId(), price.getCurrency(), price.getId());
    }
}
