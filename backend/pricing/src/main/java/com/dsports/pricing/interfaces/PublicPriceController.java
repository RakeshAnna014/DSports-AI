package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
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

    public PublicPriceController(GetPricesUseCase getPricesUseCase) {
        this.getPricesUseCase = getPricesUseCase;
    }

    @GetMapping("/{productId}")
    public Flux<PriceResult> getPricesByProduct(@PathVariable UUID productId) {
        return getPricesUseCase.execute(ProductId.fromUUID(productId));
    }

    @GetMapping
    public Flux<PriceResult> getAllPrices() {
        return getPricesUseCase.execute();
    }
}
