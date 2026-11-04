package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.result.WishlistItemResult;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.application.result.WishlistSummaryResult;
import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistItem;

import java.util.List;
import java.util.UUID;

public final class WishlistResultMapper {

    private WishlistResultMapper() {}

    public static WishlistResult toResult(Wishlist wishlist) {
        List<WishlistItemResult> itemResults = wishlist.getItems().stream()
            .map(WishlistResultMapper::toItemResult)
            .toList();

        return new WishlistResult(
            wishlist.getId().value(),
            wishlist.getUserId().value(),
            wishlist.getItemCount(),
            wishlist.getVersion(),
            itemResults,
            wishlist.getCreatedAt(),
            wishlist.getUpdatedAt()
        );
    }

    public static WishlistItemResult toItemResult(WishlistItem item) {
        return new WishlistItemResult(
            item.getId().value(),
            UUID.fromString(item.getProductId()),
            item.getProductName(),
            item.getProductImage(),
            item.getCurrentPrice().value(),
            item.getAddedAt()
        );
    }

    public static WishlistSummaryResult toSummary(Wishlist wishlist) {
        return new WishlistSummaryResult(
            wishlist.getId().value(),
            wishlist.getItemCount()
        );
    }
}
