package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.GetPriceUseCase;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import com.dsports.pricing.domain.model.PriceId;
import com.dsports.pricing.domain.model.ProductId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/prices", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicPriceController {

    private final GetPricesUseCase getPricesUseCase;
    private final GetPriceUseCase getPriceUseCase;

    public PublicPriceController(GetPricesUseCase getPricesUseCase,
                                  GetPriceUseCase getPriceUseCase) {
        this.getPricesUseCase = getPricesUseCase;
        this.getPriceUseCase = getPriceUseCase;
    }

    @GetMapping
    public Flux<PriceResult> getPrices(@RequestParam(required = false) UUID productId) {
        if (productId != null) {
            return getPricesUseCase.execute(ProductId.fromUUID(productId));
        }
        return getPricesUseCase.execute();
    }

    @GetMapping("/{id}")
    public Mono<PriceResult> getPrice(@PathVariable UUID id) {
        return getPriceUseCase.execute(PriceId.fromUUID(id));
    }
}
