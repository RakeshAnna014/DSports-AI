package com.dsports.order.interfaces.order;

import com.dsports.order.application.order.usecase.GetCoPurchasesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/insights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Order Insights")
public class CoPurchaseController {

    public record Pair(UUID productId, long orders) {}

    public record Response(UUID productId, int minimumOrders, List<Pair> coPurchased) {}

    private final GetCoPurchasesUseCase coPurchases;

    public CoPurchaseController(GetCoPurchasesUseCase coPurchases) {
        this.coPurchases = coPurchases;
    }

    @GetMapping("/co-purchases")
    @Operation(summary = "Products bought together with a product",
        description = "Anonymous aggregate over non-cancelled orders: distinct order counts only, no customer or order identity. "
            + "A product is listed only when at least minimumOrders distinct orders back it, so an empty list means there is not enough evidence.")
    public Mono<Response> coPurchases(@RequestParam UUID productId, @RequestParam(defaultValue = "0") int minOrders, @RequestParam(defaultValue = "10") int limit) {
        return coPurchases.execute(productId, minOrders, limit).map(c -> new Pair(c.productId(), c.orders())).collectList()
            .map(list -> new Response(productId, Math.max(this.coPurchases.minimumOrders(), minOrders), list));
    }
}
