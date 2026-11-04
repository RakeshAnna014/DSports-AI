package com.dsports.order.interfaces.order;

import com.dsports.order.application.order.port.SalesFeedPort;
import com.dsports.order.application.order.usecase.GetSalesFeedUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sales feed for Sales &amp; Inventory Planning. Administrators only; read-only; no customer identity. */
@RestController
@RequestMapping(path = "/api/v1/admin/sales", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Admin")
public class AdminSalesFeedController {

    public record Cursor(Instant updatedAt, UUID lineId) {}

    public record Response(List<SalesFeedPort.SalesLine> items, boolean hasMore, Cursor next, int maxPageSize) {}

    private final GetSalesFeedUseCase feed;

    public AdminSalesFeedController(GetSalesFeedUseCase feed) {
        this.feed = feed;
    }

    @GetMapping("/lines")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Sold order lines for sales planning (Admin)",
        description = "One row per order line with the order status, cancellation time, approved returned quantity, the order discount allocated to the line and the delivery city/state/country. "
            + "Pages are ordered by source version (the latest of the order's and its returns' update time) then line id: pass the previous response's next cursor as afterUpdatedAt/afterLineId to read "
            + "incrementally, and a changed order re-appears. placedFrom/placedTo (exclusive) bound the placement time. No customer name, phone, street or postal code is returned.")
    public Mono<Response> lines(@RequestParam(required = false) Instant afterUpdatedAt, @RequestParam(required = false) UUID afterLineId,
                                @RequestParam(required = false) Instant placedFrom, @RequestParam(required = false) Instant placedTo,
                                @RequestParam(defaultValue = "500") int limit) {
        return feed.execute(afterUpdatedAt, afterLineId, placedFrom, placedTo, limit)
            .map(p -> new Response(p.items(), p.hasMore(), new Cursor(p.nextUpdatedAt(), p.nextLineId()), GetSalesFeedUseCase.MAX_LIMIT));
    }
}
