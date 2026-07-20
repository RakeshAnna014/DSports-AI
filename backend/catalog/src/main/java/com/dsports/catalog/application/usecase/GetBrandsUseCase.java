package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.result.BrandResult;
import reactor.core.publisher.Flux;

public class GetBrandsUseCase {

    private final BrandRepository brandRepository;

    public GetBrandsUseCase(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public Flux<BrandResult> execute() {
        return brandRepository.findAllActive()
                .map(CreateBrandUseCase::toResult);
    }
}
