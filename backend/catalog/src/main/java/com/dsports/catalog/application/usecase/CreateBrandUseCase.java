package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateBrandCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Brand;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Mono;

public class CreateBrandUseCase {

    private final BrandRepository brandRepository;

    public CreateBrandUseCase(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public Mono<BrandResult> execute(CreateBrandCommand command) {
        var name = BrandName.from(command.name());
        var slug = Slug.from(command.slug());

        return brandRepository.existsByName(name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_BRAND_NAME,
                                "Brand with name '" + command.name() + "' already exists"));
                    }
                    return brandRepository.existsBySlug(slug);
                })
                .flatMap(slugExists -> {
                    if (slugExists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Brand with slug '" + command.slug() + "' already exists"));
                    }
                    var brand = Brand.create(name, slug, command.description());
                    return brandRepository.save(brand)
                            .thenReturn(toResult(brand));
                });
    }

    static BrandResult toResult(Brand brand) {
        return new BrandResult(
                brand.getId().value(),
                brand.getName().value(),
                brand.getSlug().value(),
                brand.getDescription(),
                brand.getStatus().name(),
                brand.getCreatedAt(),
                brand.getUpdatedAt()
        );
    }
}
