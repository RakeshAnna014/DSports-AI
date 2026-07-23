package com.dsports.order.infrastructure.order.persistence.mapper;

import com.dsports.order.domain.order.model.AddressSnapshot;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.order.infrastructure.order.persistence.entity.OrderEntity;
import com.dsports.order.infrastructure.order.persistence.entity.OrderItemEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;

public final class OrderEntityMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private OrderEntityMapper() {}

    public static OrderEntity toEntity(Order order) {
        var entity = new OrderEntity();
        entity.setId(order.getId().value());
        entity.setOrderNumber(order.getOrderNumber().value());
        entity.setUserId(order.getUserId());
        entity.setCheckoutId(order.getCheckoutId());
        entity.setStatus(order.getStatus().name());
        entity.setShippingAddressSnapshot(toJson(order.getShippingAddressSnapshot()));
        entity.setBillingAddressSnapshot(toJson(order.getBillingAddressSnapshot()));
        entity.setSubtotal(order.getSubtotal());
        entity.setShippingCharge(order.getShippingCharge());
        entity.setTaxAmount(order.getTaxAmount());
        entity.setDiscountAmount(order.getDiscountAmount());
        entity.setGrandTotal(order.getGrandTotal());
        entity.setCurrency(order.getCurrency());
        entity.setPlacedAt(order.getPlacedAt());
        entity.setCancelledAt(order.getCancelledAt());
        entity.setVersion(order.getVersion());
        entity.setCreatedAt(order.getCreatedAt());
        entity.setUpdatedAt(order.getUpdatedAt());
        return entity;
    }

    public static OrderItemEntity toItemEntity(OrderItem item) {
        var entity = new OrderItemEntity();
        entity.setId(item.getId().value());
        entity.setOrderId(item.getOrderId().value());
        entity.setProductId(item.getProductId());
        entity.setProductName(item.getProductName());
        entity.setSku(item.getSku());
        entity.setQuantity(item.getQuantity());
        entity.setUnitPrice(item.getUnitPrice());
        entity.setLineTotal(item.getLineTotal());
        entity.setProductImage(item.getProductImage());
        entity.setCreatedAt(item.getCreatedAt());
        return entity;
    }

    public static Order toDomain(OrderEntity entity, List<OrderItemEntity> itemEntities) {
        var items = itemEntities.stream()
            .map(OrderEntityMapper::toItemDomain)
            .toList();
        return Order.reconstitute(
            OrderId.fromUUID(entity.getId()),
            new OrderNumber(entity.getOrderNumber()),
            entity.getUserId(),
            entity.getCheckoutId(),
            fromJson(entity.getShippingAddressSnapshot()),
            fromJson(entity.getBillingAddressSnapshot()),
            items,
            OrderStatus.valueOf(entity.getStatus()),
            entity.getSubtotal(),
            entity.getShippingCharge(),
            entity.getTaxAmount(),
            entity.getDiscountAmount(),
            entity.getGrandTotal(),
            entity.getCurrency(),
            entity.getPlacedAt(),
            entity.getCancelledAt(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public static OrderItem toItemDomain(OrderItemEntity entity) {
        return new OrderItem(
            OrderItemId.fromUUID(entity.getId()),
            OrderId.fromUUID(entity.getOrderId()),
            entity.getProductId(),
            entity.getProductName(),
            entity.getSku(),
            entity.getQuantity(),
            entity.getUnitPrice(),
            entity.getLineTotal(),
            entity.getProductImage(),
            entity.getCreatedAt()
        );
    }

    private static String toJson(AddressSnapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AddressSnapshot", e);
        }
    }

    private static AddressSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, AddressSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize AddressSnapshot", e);
        }
    }
}
