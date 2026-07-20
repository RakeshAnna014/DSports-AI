package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.GetInventoriesUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.inventory.application.usecase.GetInventoryUseCase;
import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.ProductId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicInventoryController {

    private final GetInventoryUseCase getInventoryUseCase;
    private final GetInventoriesUseCase getInventoriesUseCase;
    private final GetInventoryByProductUseCase getInventoryByProductUseCase;

    public PublicInventoryController(GetInventoryUseCase getInventoryUseCase,
                                     GetInventoriesUseCase getInventoriesUseCase,
                                     GetInventoryByProductUseCase getInventoryByProductUseCase) {
        this.getInventoryUseCase = getInventoryUseCase;
        this.getInventoriesUseCase = getInventoriesUseCase;
        this.getInventoryByProductUseCase = getInventoryByProductUseCase;
    }

    @GetMapping("/{productId}")
    public Flux<InventoryResult> getInventoryByProduct(@PathVariable UUID productId) {
        return getInventoryByProductUseCase.execute(ProductId.fromUUID(productId));
    }

    @GetMapping
    public Flux<InventoryResult> getAllInventory() {
        return getInventoriesUseCase.execute();
    }
}
