package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.BrandId;
import reactor.core.publisher.Mono;

public class GetBrandUseCase {

    private final BrandRepository brandRepository;

    public GetBrandUseCase(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public Mono<BrandResult> execute(BrandId id) {
        return brandRepository.findById(id)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.BRAND_NOT_FOUND,
                        "Brand not found: " + id)))
                .map(CreateBrandUseCase::toResult);
    }
}
