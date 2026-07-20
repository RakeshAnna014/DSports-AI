package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveBrandCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import reactor.core.publisher.Mono;

public class ArchiveBrandUseCase {

    private final BrandRepository brandRepository;

    public ArchiveBrandUseCase(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public Mono<BrandResult> execute(ArchiveBrandCommand command) {
        return brandRepository.findById(command.brandId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.BRAND_NOT_FOUND,
                        "Brand not found: " + command.brandId())))
                .flatMap(brand -> {
                    brand.archive();
                    return brandRepository.save(brand)
                            .thenReturn(CreateBrandUseCase.toResult(brand));
                });
    }
}
