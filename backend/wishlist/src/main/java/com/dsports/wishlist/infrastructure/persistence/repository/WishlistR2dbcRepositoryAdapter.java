package com.dsports.wishlist.infrastructure.persistence.repository;

import com.dsports.wishlist.application.port.EventPublisher;
import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.infrastructure.persistence.entity.WishlistEntity;
import com.dsports.wishlist.infrastructure.persistence.entity.WishlistItemEntity;
import com.dsports.wishlist.infrastructure.persistence.mapper.WishlistEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class WishlistR2dbcRepositoryAdapter implements WishlistRepository {
    private final SpringR2dbcWishlistRepository springRepository;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator transactionalOperator;
    private final R2dbcEntityTemplate entityTemplate;

    public WishlistR2dbcRepositoryAdapter(SpringR2dbcWishlistRepository springRepository,
                                          EventPublisher eventPublisher,
                                          ReactiveTransactionManager transactionManager,
                                          R2dbcEntityTemplate entityTemplate) {
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
        this.entityTemplate = entityTemplate;
    }

    @Override
    public Mono<Wishlist> findById(WishlistId id) {
        return springRepository.findById(id.value())
            .flatMap(this::loadItems);
    }

    @Override
    public Mono<Wishlist> findByUserId(UserId userId) {
        return springRepository.findByUserId(userId.value())
            .flatMap(this::loadItems);
    }

    @Override
    public Mono<Boolean> existsByUserId(UserId userId) {
        return springRepository.existsByUserId(userId.value());
    }

    @Override
    public Mono<Void> save(Wishlist wishlist) {
        var wishlistEntity = WishlistEntityMapper.toEntity(wishlist);
        var wishlistId = wishlist.getId().value();

        return transactionalOperator.execute(tx ->
            springRepository.save(wishlistEntity)
                .onErrorResume(OptimisticLockingFailureException.class, e ->
                    Mono.error(new WishlistDomainException(WishlistErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "Concurrent modification detected for wishlist " + wishlistId)))
                .onErrorResume(DataIntegrityViolationException.class, e ->
                    handleConstraintViolation(e))
                .flatMap(savedEntity -> {
                    wishlist.setVersion(savedEntity.getVersion());
                    return replaceItems(wishlistId, wishlist);
                })
                .then()
        ).then(Mono.fromRunnable(() -> publishEvents(wishlist)));
    }

    private Mono<Void> replaceItems(UUID wishlistId, Wishlist wishlist) {
        return springRepository.clearItems(wishlistId)
            .thenMany(Flux.fromIterable(wishlist.getItems()))
            .map(WishlistEntityMapper::toItemEntity)
            .flatMap(entityTemplate::insert)
            .then();
    }

    private <T> Mono<T> handleConstraintViolation(DataIntegrityViolationException e) {
        if (e.getMessage() != null && e.getMessage().contains("idx_wishlists_user_id")) {
            return Mono.error(new WishlistDomainException(WishlistErrorCode.DUPLICATE_WISHLIST,
                "User already has a wishlist"));
        }
        if (e.getMessage() != null && e.getMessage().contains("idx_wishlist_items_unique_product")) {
            return Mono.error(new WishlistDomainException(WishlistErrorCode.DUPLICATE_WISHLIST_ITEM,
                "Product already exists in wishlist"));
        }
        return Mono.error(new WishlistDomainException(WishlistErrorCode.GENERIC,
            "Database constraint violation: " + e.getMessage()));
    }

    private void publishEvents(Wishlist wishlist) {
        if (!wishlist.getDomainEvents().isEmpty()) {
            eventPublisher.publishAll(wishlist.getDomainEvents());
            wishlist.clearDomainEvents();
        }
    }

    private Mono<Wishlist> loadItems(WishlistEntity entity) {
        return springRepository.findItemsByWishlistId(entity.getId())
            .collectList()
            .map(items -> WishlistEntityMapper.toDomain(entity, items));
    }

    @Override
    public Mono<Void> deleteItem(WishlistId wishlistId, UUID itemId) {
        return springRepository.deleteItem(wishlistId.value(), itemId).then();
    }

    @Override
    public Mono<Void> clear(WishlistId wishlistId) {
        return springRepository.clearItems(wishlistId.value()).then();
    }
}
