package com.dsports.config;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.returns.application.port.ReturnOrderPort;
import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.application.usecase.CheckReturnEligibilityUseCase;
import com.dsports.returns.application.usecase.CreateReturnUseCase;
import com.dsports.returns.application.usecase.ManageReturnsUseCase;
import com.dsports.returns.domain.policy.ReturnPolicy;
import com.dsports.returns.infrastructure.persistence.ReturnR2dbcRepositoryAdapter;
import com.dsports.returns.infrastructure.persistence.SpringR2dbcReturnRepository;
import com.dsports.shared.application.TransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Collectors;

@Configuration
public class ReturnsModuleConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ReturnRepository returnRepository(SpringR2dbcReturnRepository springRepository, DatabaseClient databaseClient) {
        return new ReturnR2dbcRepositoryAdapter(springRepository, databaseClient);
    }

    @Bean
    public ReturnPolicy returnPolicy(@Value("${app.returns.window:P7D}") Duration window) {
        return new ReturnPolicy(window);
    }

    /**
     * Owner-scoped view of an order. The order has no dedicated delivery timestamp, so the last status change of a
     * DELIVERED order (which can only move on to REFUNDED) is used as the delivery time.
     */
    @Bean
    public ReturnOrderPort returnOrderPort(OrderRepository orderRepository) {
        return (orderId, userId) -> orderRepository.findById(new OrderId(orderId))
            .filter(order -> order.getUserId().equals(userId))
            .map(order -> new ReturnPolicy.OrderFacts(
                order.getId().value(),
                order.getStatus() == OrderStatus.DELIVERED,
                order.getStatus().name(),
                order.getUpdatedAt(),
                order.getCurrency(),
                order.getItems().stream().collect(Collectors.toMap(
                    OrderItem::getProductId,
                    item -> new ReturnPolicy.OrderItemFacts(item.getProductName(), item.getQuantity(), item.getUnitPrice()),
                    (a, b) -> new ReturnPolicy.OrderItemFacts(a.productName(), a.quantity() + b.quantity(), a.unitPrice())))));
    }

    /** Approved returned quantities for reporting. The returns module owns the table; the order module's sales feed reads it only through this port. */
    @Bean
    public com.dsports.returns.application.port.ApprovedReturnsReadPort approvedReturnsReadPort(DatabaseClient databaseClient) {
        return new com.dsports.returns.infrastructure.persistence.ApprovedReturnsR2dbcReader(databaseClient);
    }

    @Bean
    public com.dsports.order.application.order.port.ApprovedReturnsPort approvedReturnsPort(com.dsports.returns.application.port.ApprovedReturnsReadPort read) {
        return orderIds -> read.approvedFor(orderIds)
            .map(a -> new com.dsports.order.application.order.port.ApprovedReturnsPort.ApprovedReturn(a.orderId(), a.productId(), a.quantity()));
    }

    @Bean
    public CheckReturnEligibilityUseCase checkReturnEligibilityUseCase(ReturnOrderPort orderPort, ReturnRepository repository,
                                                                      ReturnPolicy policy, Clock clock) {
        return new CheckReturnEligibilityUseCase(orderPort, repository, policy, clock);
    }

    @Bean
    public CreateReturnUseCase createReturnUseCase(ReturnOrderPort orderPort, ReturnRepository repository, ReturnPolicy policy,
                                                   TransactionRunner transactionRunner, Clock clock) {
        return new CreateReturnUseCase(orderPort, repository, policy, transactionRunner, clock);
    }

    @Bean
    public ManageReturnsUseCase manageReturnsUseCase(ReturnRepository repository) {
        return new ManageReturnsUseCase(repository);
    }
}
