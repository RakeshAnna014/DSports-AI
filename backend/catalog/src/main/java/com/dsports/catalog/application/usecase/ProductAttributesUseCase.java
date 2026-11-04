package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.ProductAttributeRepository;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.ProductId;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads and (admin) replaces the structured facts of a product, e.g. skillLevel, material, surface. */
public class ProductAttributesUseCase {

    private static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,49}");
    private static final int MAX_ATTRIBUTES = 30;
    private static final int MAX_VALUE_LENGTH = 200;

    private final ProductRepository productRepository;
    private final ProductAttributeRepository attributeRepository;

    public ProductAttributesUseCase(ProductRepository productRepository, ProductAttributeRepository attributeRepository) {
        this.productRepository = productRepository;
        this.attributeRepository = attributeRepository;
    }

    public Mono<Map<String, String>> get(ProductId id) {
        return ensureExists(id).then(Mono.defer(() -> attributeRepository.findByProductId(id)));
    }

    public Mono<Map<UUID, Map<String, String>>> getAll() {
        return attributeRepository.findAll();
    }

    public Mono<Map<String, String>> replace(ProductId id, Map<String, String> attributes) {
        validate(attributes);
        return ensureExists(id)
                .then(Mono.defer(() -> attributeRepository.replace(id, attributes)))
                .then(Mono.defer(() -> attributeRepository.findByProductId(id)));
    }

    private Mono<Void> ensureExists(ProductId id) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + id.value())))
                .then();
    }

    private static void validate(Map<String, String> attributes) {
        if (attributes == null || attributes.size() > MAX_ATTRIBUTES) {
            throw new CatalogDomainException(CatalogErrorCode.VALIDATION_ERROR,
                    "A product can have at most " + MAX_ATTRIBUTES + " attributes");
        }
        attributes.forEach((key, value) -> {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new CatalogDomainException(CatalogErrorCode.VALIDATION_ERROR,
                        "Invalid attribute key '" + key + "': use letters, digits and underscores, starting with a letter");
            }
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                throw new CatalogDomainException(CatalogErrorCode.VALIDATION_ERROR,
                        "Attribute '" + key + "' must have a value of 1-" + MAX_VALUE_LENGTH + " characters");
            }
        });
    }
}
