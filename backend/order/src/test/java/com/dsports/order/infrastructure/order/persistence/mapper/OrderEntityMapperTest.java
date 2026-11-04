package com.dsports.order.infrastructure.order.persistence.mapper;

import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEntityMapperTest {

    @Test
    void itemEntityIsMarkedNewSoSpringDataInsertsInsteadOfUpdating() {
        var item = OrderItem.create(OrderItemId.generate(), OrderId.generate(), UUID.randomUUID(),
            "Bat", "SKU-1", 1, new BigDecimal("10.00"), new BigDecimal("10.00"), null);

        var entity = OrderEntityMapper.toItemEntity(item);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.isNew()).isTrue();
    }
}
