package com.dsports.wishlist.infrastructure.config;

import com.dsports.wishlist.application.port.CartPort;
import com.dsports.wishlist.application.port.EventPublisher;
import com.dsports.wishlist.application.port.ProductCatalogPort;
import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.usecase.AddWishlistItemUseCase;
import com.dsports.wishlist.application.usecase.ClearWishlistUseCase;
import com.dsports.wishlist.application.usecase.CreateWishlistUseCase;
import com.dsports.wishlist.application.usecase.GetWishlistCountUseCase;
import com.dsports.wishlist.application.usecase.GetWishlistUseCase;
import com.dsports.wishlist.application.usecase.MoveWishlistItemToCartUseCase;
import com.dsports.wishlist.application.usecase.RemoveWishlistItemUseCase;
import com.dsports.wishlist.infrastructure.event.WishlistSpringEventPublisherAdapter;
import com.dsports.wishlist.infrastructure.persistence.repository.SpringR2dbcWishlistRepository;
import com.dsports.wishlist.infrastructure.persistence.repository.WishlistR2dbcRepositoryAdapter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.ReactiveTransactionManager;

@Configuration
public class WishlistInfrastructureConfiguration {

    private final SpringR2dbcWishlistRepository springRepository;
    private final ReactiveTransactionManager transactionManager;
    private final R2dbcEntityTemplate entityTemplate;
    private final ProductCatalogPort productCatalogPort;
    private final CartPort cartPort;

    public WishlistInfrastructureConfiguration(SpringR2dbcWishlistRepository springRepository,
                                               ReactiveTransactionManager transactionManager,
                                               R2dbcEntityTemplate entityTemplate,
                                               ProductCatalogPort productCatalogPort,
                                               CartPort cartPort) {
        this.springRepository = springRepository;
        this.transactionManager = transactionManager;
        this.entityTemplate = entityTemplate;
        this.productCatalogPort = productCatalogPort;
        this.cartPort = cartPort;
    }

    @Bean
    public EventPublisher wishlistEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new WishlistSpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public WishlistRepository wishlistRepository(EventPublisher wishlistEventPublisher) {
        return new WishlistR2dbcRepositoryAdapter(springRepository, wishlistEventPublisher,
            transactionManager, entityTemplate);
    }

    @Bean
    public CreateWishlistUseCase createWishlistUseCase(WishlistRepository wishlistRepository) {
        return new CreateWishlistUseCase(wishlistRepository);
    }

    @Bean
    public AddWishlistItemUseCase addWishlistItemUseCase(WishlistRepository wishlistRepository) {
        return new AddWishlistItemUseCase(wishlistRepository, productCatalogPort);
    }

    @Bean
    public RemoveWishlistItemUseCase removeWishlistItemUseCase(WishlistRepository wishlistRepository) {
        return new RemoveWishlistItemUseCase(wishlistRepository);
    }

    @Bean
    public MoveWishlistItemToCartUseCase moveWishlistItemToCartUseCase(
            WishlistRepository wishlistRepository) {
        return new MoveWishlistItemToCartUseCase(wishlistRepository, cartPort);
    }

    @Bean
    public ClearWishlistUseCase clearWishlistUseCase(WishlistRepository wishlistRepository) {
        return new ClearWishlistUseCase(wishlistRepository);
    }

    @Bean
    public GetWishlistUseCase getWishlistUseCase(WishlistRepository wishlistRepository) {
        return new GetWishlistUseCase(wishlistRepository);
    }

    @Bean
    public GetWishlistCountUseCase getWishlistCountUseCase(WishlistRepository wishlistRepository) {
        return new GetWishlistCountUseCase(wishlistRepository);
    }
}
