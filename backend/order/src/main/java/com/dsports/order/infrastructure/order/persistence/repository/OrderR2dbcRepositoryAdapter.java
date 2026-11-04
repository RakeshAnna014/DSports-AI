package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.infrastructure.order.persistence.mapper.OrderEntityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import com.dsports.order.domain.order.model.OrderStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
public class OrderR2dbcRepositoryAdapter implements OrderRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderR2dbcRepositoryAdapter.class);

    private final SpringR2dbcOrderRepository orderRepo;
    private final SpringR2dbcOrderItemRepository itemRepo;
    private final TransactionalOperator transactionalOperator;

    public OrderR2dbcRepositoryAdapter(SpringR2dbcOrderRepository orderRepo,
                                        SpringR2dbcOrderItemRepository itemRepo,
                                        ReactiveTransactionManager transactionManager) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public Mono<Order> findById(OrderId id) {
        return orderRepo.findById(id.value())
            .flatMap(entity -> itemRepo.findByOrderId(id.value())
                .collectList()
                .map(items -> OrderEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Mono<Order> findByOrderNumber(OrderNumber orderNumber) {
        return orderRepo.findByOrderNumber(orderNumber.value())
            .flatMap(entity -> itemRepo.findByOrderId(entity.getId())
                .collectList()
                .map(items -> OrderEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Flux<Order> findByUserId(UUID userId) {
        return orderRepo.findByUserIdOrderByPlacedAtDesc(userId)
            .flatMap(entity -> itemRepo.findByOrderId(entity.getId())
                .collectList()
                .map(items -> OrderEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Mono<Boolean> existsByCheckoutId(UUID checkoutId) {
        return orderRepo.existsByCheckoutId(checkoutId);
    }

    @Override
    public Mono<Long> nextOrderSequence() {
        return orderRepo.nextOrderSequence();
    }

    @Override
    public Flux<Order> findByStatusPlacedBefore(OrderStatus status, Instant cutoff) {
        return orderRepo.findByStatusAndPlacedAtBefore(status.name(), cutoff)
            .flatMap(entity -> itemRepo.findByOrderId(entity.getId())
                .collectList()
                .map(items -> OrderEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Mono<Void> save(Order order) {
        var entity = OrderEntityMapper.toEntity(order);
        var itemEntities = order.getItems().stream()
            .map(OrderEntityMapper::toItemEntity)
            .toList();

        return transactionalOperator.execute(tx ->
            // The entity carries the version the order was loaded with (0 for a new order), so Spring Data
            // inserts new orders and updates existing ones only if nobody changed them in between.
            orderRepo.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                    new OrderDomainException(OrderErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "Order " + order.getId().value() + " was modified concurrently. Reload and retry."))
                .onErrorMap(DataIntegrityViolationException.class, e -> isDuplicateCheckout(e)
                    ? new OrderDomainException(OrderErrorCode.DUPLICATE_ORDER,
                        "An order for checkout " + order.getCheckoutId() + " already exists")
                    : e)
                .flatMap(savedEntity -> {
                    order.setVersion(savedEntity.getVersion());
                    return itemRepo.deleteByOrderId(order.getId().value())
                        .thenMany(itemRepo.saveAll(itemEntities))
                        .then();
                })
        ).then();
    }

    private static boolean isDuplicateCheckout(DataIntegrityViolationException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_orders_checkout_id");
    }
}
