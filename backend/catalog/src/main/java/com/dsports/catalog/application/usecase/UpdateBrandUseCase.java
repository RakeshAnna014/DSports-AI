package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateBrandCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Mono;

public class UpdateBrandUseCase {

    private final BrandRepository brandRepository;

    public UpdateBrandUseCase(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public Mono<BrandResult> execute(UpdateBrandCommand command) {
        return brandRepository.findById(command.brandId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.BRAND_NOT_FOUND,
                        "Brand not found: " + command.brandId())))
                .flatMap(brand -> {
                    var newName = BrandName.from(command.name());
                    var newSlug = Slug.from(command.slug());

                    var nameCheck = brand.getName().value().equals(command.name())
                            ? Mono.just(false)
                            : brandRepository.existsByName(newName);

                    var slugCheck = brand.getSlug().value().equals(command.slug())
                            ? Mono.just(false)
                            : brandRepository.existsBySlug(newSlug);

                    return Mono.zip(nameCheck, slugCheck)
                            .flatMap(tuple -> {
                                if (tuple.getT1()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_BRAND_NAME,
                                            "Brand with name '" + command.name() + "' already exists"));
                                }
                                if (tuple.getT2()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                            "Brand with slug '" + command.slug() + "' already exists"));
                                }
                                try {
                                    brand.update(newName, newSlug, command.description());
                                } catch (IllegalStateException e) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                            "Cannot update an archived brand"));
                                }
                                return brandRepository.save(brand)
                                        .thenReturn(CreateBrandUseCase.toResult(brand));
                            });
                });
    }
}
