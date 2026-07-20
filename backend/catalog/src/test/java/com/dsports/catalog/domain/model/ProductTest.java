package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class ProductTest {

    private static final BrandId BRAND_ID = BrandId.generate();
    private static final CategoryId CATEGORY_ID = CategoryId.generate();
    private static final SportId SPORT_ID = SportId.generate();

    @Test
    void shouldCreateProduct() {
        var product = Product.create(
                SKU.from("BAT-001"),
                ProductName.from("Cricket Bat"),
                Slug.from("cricket-bat"),
                ProductDescription.from("A quality cricket bat"),
                BRAND_ID, CATEGORY_ID, SPORT_ID,
                Weight.from(BigDecimal.valueOf(1.2), "kg"),
                Dimensions.from(BigDecimal.valueOf(36), BigDecimal.valueOf(4), BigDecimal.valueOf(2), "in")
        );

        assertThat(product.getId()).isNotNull();
        assertThat(product.getSku().value()).isEqualTo("BAT-001");
        assertThat(product.getName().value()).isEqualTo("Cricket Bat");
        assertThat(product.getSlug().value()).isEqualTo("cricket-bat");
        assertThat(product.getBrandId()).isEqualTo(BRAND_ID);
        assertThat(product.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(product.getSportId()).isEqualTo(SPORT_ID);
        assertThat(product.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(product.getVersion()).isZero();
        assertThat(product.getImages()).isEmpty();
        assertThat(product.getDomainEvents()).hasSize(1);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(ProductCreatedEvent.class);
    }

    @Test
    void shouldUpdateProduct() {
        var product = createProduct();
        product.clearDomainEvents();

        var newBrand = BrandId.generate();
        product.update(
                SKU.from("UPD-001"),
                ProductName.from("Updated Bat"),
                Slug.from("updated-bat"),
                ProductDescription.from("Updated description"),
                newBrand, CATEGORY_ID, SPORT_ID,
                null, null
        );

        assertThat(product.getName().value()).isEqualTo("Updated Bat");
        assertThat(product.getSlug().value()).isEqualTo("updated-bat");
        assertThat(product.getBrandId()).isEqualTo(newBrand);
        assertThat(product.getDomainEvents()).hasSize(1);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(ProductUpdatedEvent.class);
    }

    @Test
    void shouldArchiveProduct() {
        var product = createProduct();
        product.clearDomainEvents();

        product.archive();

        assertThat(product.getStatus()).isEqualTo(Status.ARCHIVED);
        assertThat(product.getDomainEvents()).hasSize(1);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(ProductArchivedEvent.class);
    }

    @Test
    void shouldThrowWhenArchivingAlreadyArchived() {
        var product = createProduct();
        product.archive();

        assertThatThrownBy(product::archive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already archived");
    }

    @Test
    void shouldThrowWhenUpdatingArchived() {
        var product = createProduct();
        product.archive();

        assertThatThrownBy(() -> product.update(
                SKU.from("NEW-SKU"), ProductName.from("New"), Slug.from("new"), ProductDescription.from(""),
                BRAND_ID, CATEGORY_ID, SPORT_ID, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void shouldAddImage() {
        var product = createProduct();
        product.clearDomainEvents();

        product.addImage(ProductImageUrl.from("https://example.com/image.jpg"), 1, true);

        assertThat(product.getImages()).hasSize(1);
        var image = product.getImages().get(0);
        assertThat(image.isPrimary()).isTrue();
        assertThat(image.getDisplayOrder()).isEqualTo(1);
        assertThat(product.getDomainEvents()).hasSize(2);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(ProductImageAddedEvent.class);
        assertThat(product.getDomainEvents().get(1)).isInstanceOf(PrimaryImageChangedEvent.class);
    }

    @Test
    void shouldRemoveImage() {
        var product = createProduct();
        product.addImage(ProductImageUrl.from("https://example.com/image.jpg"), 1, true);
        var imageId = product.getImages().get(0).getId();
        product.clearDomainEvents();

        product.removeImage(imageId);

        assertThat(product.getImages()).isEmpty();
        assertThat(product.getDomainEvents()).hasSize(1);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(ProductImageRemovedEvent.class);
    }

    @Test
    void shouldChangePrimaryImage() {
        var product = createProduct();
        product.addImage(ProductImageUrl.from("https://example.com/img1.jpg"), 1, true);
        product.addImage(ProductImageUrl.from("https://example.com/img2.jpg"), 2, false);
        var newPrimaryId = product.getImages().get(1).getId();
        product.clearDomainEvents();

        product.changePrimaryImage(newPrimaryId);

        assertThat(product.getImages().get(0).isPrimary()).isFalse();
        assertThat(product.getImages().get(1).isPrimary()).isTrue();
        assertThat(product.getDomainEvents()).hasSize(1);
        assertThat(product.getDomainEvents().get(0)).isInstanceOf(PrimaryImageChangedEvent.class);
    }

    @Test
    void shouldRejectAddingImageToArchivedProduct() {
        var product = createProduct();
        product.archive();

        assertThatThrownBy(() -> product.addImage(ProductImageUrl.from("https://example.com/img.jpg"), 1, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void shouldRejectMoreThanMaxImages() {
        var product = createProduct();
        for (int i = 0; i < 20; i++) {
            product.addImage(ProductImageUrl.from("https://example.com/img" + i + ".jpg"), i, i == 0);
        }

        assertThatThrownBy(() -> product.addImage(ProductImageUrl.from("https://example.com/extra.jpg"), 21, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("20");
    }

    @Test
    void shouldRejectRemovingNonExistentImage() {
        var product = createProduct();

        assertThatThrownBy(() -> product.removeImage(ProductImageId.generate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldRejectChangingNonExistentPrimaryImage() {
        var product = createProduct();

        assertThatThrownBy(() -> product.changePrimaryImage(ProductImageId.generate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shouldRejectNullValues() {
        assertThatThrownBy(() -> Product.create(null, ProductName.from("N"), Slug.from("s"),
                ProductDescription.from(""), BRAND_ID, CATEGORY_ID, SPORT_ID, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Product createProduct() {
        return Product.create(
                SKU.from("BAT-001"),
                ProductName.from("Cricket Bat"),
                Slug.from("cricket-bat"),
                ProductDescription.from("A quality cricket bat"),
                BRAND_ID, CATEGORY_ID, SPORT_ID,
                null, null
        );
    }
}
