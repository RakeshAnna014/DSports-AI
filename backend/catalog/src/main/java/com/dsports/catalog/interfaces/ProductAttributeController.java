package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.usecase.ProductAttributesUseCase;
import com.dsports.catalog.domain.model.ProductId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/** Structured product facts used by shoppers and by the shopping assistant. */
@RestController
@Tag(name = "Product Attributes")
public class ProductAttributeController {

    private final ProductAttributesUseCase useCase;

    public ProductAttributeController(ProductAttributesUseCase useCase) {
        this.useCase = useCase;
    }

    public record ProductAttributesResponse(UUID productId, Map<String, String> attributes) {}

    public record ReplaceAttributesRequest(@NotNull Map<String, String> attributes) {}

    @GetMapping(path = "/api/catalog/products/{id}/attributes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Structured facts of a product (skill level, material, surface, ...)")
    public Mono<ProductAttributesResponse> get(@Parameter(description = "Product ID") @PathVariable UUID id) {
        return useCase.get(ProductId.fromUUID(id)).map(attrs -> new ProductAttributesResponse(id, attrs));
    }

    @GetMapping(path = "/api/catalog/product-attributes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Structured facts of every product, keyed by product id (one request for filters and listings)")
    public Mono<Map<UUID, Map<String, String>>> getAll() {
        return useCase.getAll();
    }

    @PutMapping(path = "/api/admin/catalog/products/{id}/attributes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Replace the attributes of a product (admin)")
    public Mono<ProductAttributesResponse> replace(@PathVariable UUID id,
                                                   @Valid @RequestBody ReplaceAttributesRequest request) {
        return useCase.replace(ProductId.fromUUID(id), request.attributes())
                .map(attrs -> new ProductAttributesResponse(id, attrs));
    }
}
