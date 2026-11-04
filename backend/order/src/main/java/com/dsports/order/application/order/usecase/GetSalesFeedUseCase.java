package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.port.ApprovedReturnsPort;
import com.dsports.order.application.order.port.SalesFeedPort;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A page of the sales feed. The page size is bounded here, never by the caller; one extra row tells whether more follow. Approved returns are added per order and product; when the
 * same product appears on several lines of one order its returns are attributed to the first line only, so a return is never counted twice.
 */
public class GetSalesFeedUseCase {

    public static final int MAX_LIMIT = 1000;
    private static final UUID NIL = new UUID(0L, 0L);

    public record Page(List<SalesFeedPort.SalesLine> items, boolean hasMore, Instant nextUpdatedAt, UUID nextLineId) {}

    private final SalesFeedPort port;
    private final ApprovedReturnsPort returns;

    public GetSalesFeedUseCase(SalesFeedPort port, ApprovedReturnsPort returns) {
        this.port = port;
        this.returns = returns;
    }

    public Mono<Page> execute(Instant afterUpdatedAt, UUID afterLineId, Instant placedFrom, Instant placedTo, int limit) {
        int n = Math.min(Math.max(1, limit), MAX_LIMIT);
        Instant ts = afterUpdatedAt == null ? Instant.EPOCH : afterUpdatedAt;
        UUID id = afterLineId == null ? NIL : afterLineId;
        return port.linesAfter(ts, id, placedFrom, placedTo, n + 1).collectList().flatMap(rows -> {
            boolean more = rows.size() > n;
            List<SalesFeedPort.SalesLine> raw = more ? rows.subList(0, n) : rows;
            Set<UUID> orders = raw.stream().map(SalesFeedPort.SalesLine::orderId).collect(Collectors.toSet());
            Mono<Map<String, Long>> approved = orders.isEmpty() ? Mono.just(Map.<String, Long>of())
                : returns.approvedFor(orders).collectMap(r -> r.orderId() + "|" + r.productId(), ApprovedReturnsPort.ApprovedReturn::quantity);
            return approved.map(byKey -> {
                Map<String, Boolean> used = new HashMap<>();
                List<SalesFeedPort.SalesLine> items = raw.stream().map(l -> {
                    String key = l.orderId() + "|" + l.productId();
                    if (used.putIfAbsent(key, true) != null) return l;
                    return l.withApprovedReturnQuantity(byKey.getOrDefault(key, 0L).intValue());
                }).toList();
                SalesFeedPort.SalesLine last = items.isEmpty() ? null : items.get(items.size() - 1);
                return new Page(List.copyOf(items), more, last == null ? ts : last.sourceUpdatedAt(), last == null ? id : last.lineId());
            });
        });
    }
}
