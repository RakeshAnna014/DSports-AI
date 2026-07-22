package com.dsports.cart.infrastructure.persistence.repository;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.port.EventPublisher;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.Cart;
import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.UserId;
import com.dsports.cart.infrastructure.persistence.entity.CartEntity;
import com.dsports.cart.infrastructure.persistence.entity.CartItemEntity;
import com.dsports.cart.infrastructure.persistence.mapper.CartEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class CartR2dbcRepositoryAdapter implements CartRepository {
    private final SpringR2dbcCartRepository springRepository;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator transactionalOperator;
    private final R2dbcEntityTemplate entityTemplate;

    public CartR2dbcRepositoryAdapter(SpringR2dbcCartRepository springRepository,
                                      EventPublisher eventPublisher,
                                      ReactiveTransactionManager transactionManager,
                                      R2dbcEntityTemplate entityTemplate) {
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
        this.entityTemplate = entityTemplate;
    }

    @Override
    public Mono<Cart> findById(CartId id) {
        return springRepository.findById(id.value())
            .flatMap(this::loadItems);
    }

    @Override
    public Mono<Cart> findByUserId(UserId userId) {
        return springRepository.findActiveByUserId(userId.value())
            .flatMap(this::loadItems);
    }

    @Override
    public Mono<Boolean> existsActiveCartByUserId(UserId userId) {
        return springRepository.existsActiveByUserId(userId.value());
    }

    @Override
    public Mono<Void> save(Cart cart) {
        var cartEntity = CartEntityMapper.toEntity(cart);
        var cartId = cart.getId().value();

        return transactionalOperator.execute(tx ->
            springRepository.save(cartEntity)
                .onErrorResume(OptimisticLockingFailureException.class, e ->
                    Mono.error(new CartDomainException(CartErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "Concurrent modification detected for cart " + cartId)))
                .onErrorResume(DataIntegrityViolationException.class, e ->
                    handleConstraintViolation(e))
                .flatMap(savedEntity -> {
                    cart.setVersion(savedEntity.getVersion());
                    return replaceItems(cartId, cart);
                })
                .then()
        ).then(Mono.fromRunnable(() -> publishEvents(cart)));
    }

    private Mono<Void> replaceItems(UUID cartId, Cart cart) {
        return springRepository.clearItems(cartId)
            .thenMany(Flux.fromIterable(cart.getItems()))
            .map(CartEntityMapper::toItemEntity)
            .flatMap(entityTemplate::insert)
            .then();
    }

    private <T> Mono<T> handleConstraintViolation(DataIntegrityViolationException e) {
        if (e.getMessage() != null && e.getMessage().contains("uq_carts_user_active")) {
            return Mono.error(new CartDomainException(CartErrorCode.DUPLICATE_ACTIVE_CART,
                "User already has an active cart"));
        }
        return Mono.error(new CartDomainException(CartErrorCode.GENERIC,
            "Database constraint violation: " + e.getMessage()));
    }

    private void publishEvents(Cart cart) {
        if (!cart.getDomainEvents().isEmpty()) {
            eventPublisher.publishAll(cart.getDomainEvents());
            cart.clearDomainEvents();
        }
    }

    private Mono<Cart> loadItems(CartEntity entity) {
        return springRepository.findItemsByCartId(entity.getId())
            .collectList()
            .map(items -> CartEntityMapper.toDomain(entity, items));
    }

    @Override
    public Mono<Void> deleteItem(CartId cartId, UUID itemId) {
        return springRepository.deleteItem(cartId.value(), itemId).then();
    }

    @Override
    public Mono<Void> clear(CartId cartId) {
        return springRepository.clearItems(cartId.value()).then();
    }
}
