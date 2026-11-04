package com.dsports.wishlist.infrastructure.persistence.mapper;

import com.dsports.wishlist.domain.model.Price;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.domain.model.WishlistItem;
import com.dsports.wishlist.domain.model.WishlistItemId;
import com.dsports.wishlist.infrastructure.persistence.entity.WishlistEntity;
import com.dsports.wishlist.infrastructure.persistence.entity.WishlistItemEntity;

import java.util.List;
import java.util.UUID;

public final class WishlistEntityMapper {

    private WishlistEntityMapper() {}

    public static WishlistEntity toEntity(Wishlist wishlist) {
        var entity = new WishlistEntity();
        entity.setId(wishlist.getId().value());
        entity.setUserId(wishlist.getUserId().value());
        entity.setVersion(wishlist.getVersion());
        entity.setCreatedAt(wishlist.getCreatedAt());
        entity.setUpdatedAt(wishlist.getUpdatedAt());
        return entity;
    }

    public static WishlistItemEntity toItemEntity(WishlistItem item) {
        var entity = new WishlistItemEntity();
        entity.setId(item.getId().value());
        entity.setWishlistId(item.getWishlistId().value());
        entity.setProductId(UUID.fromString(item.getProductId()));
        entity.setProductName(item.getProductName());
        entity.setProductImage(item.getProductImage());
        entity.setCurrentPrice(item.getCurrentPrice().value());
        entity.setAddedAt(item.getAddedAt());
        return entity;
    }

    public static Wishlist toDomain(WishlistEntity entity, List<WishlistItemEntity> itemEntities) {
        var items = itemEntities.stream()
            .map(WishlistEntityMapper::toItemDomain)
            .toList();
        var wishlistId = WishlistId.fromUUID(entity.getId());
        var userId = UserId.fromUUID(entity.getUserId());
        return Wishlist.reconstitute(
            wishlistId,
            userId,
            items,
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public static WishlistItem toItemDomain(WishlistItemEntity entity) {
        return WishlistItem.reconstitute(
            WishlistItemId.fromUUID(entity.getId()),
            WishlistId.fromUUID(entity.getWishlistId()),
            entity.getProductId().toString(),
            entity.getProductName(),
            entity.getProductImage(),
            entity.getCurrentPrice(),
            entity.getAddedAt()
        );
    }
}
