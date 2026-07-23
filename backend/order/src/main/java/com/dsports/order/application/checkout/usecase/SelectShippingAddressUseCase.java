package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.command.SelectShippingAddressCommand;
import com.dsports.order.application.checkout.port.AddressPort;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.CheckoutId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SelectShippingAddressUseCase {
    private static final Logger log = LoggerFactory.getLogger(SelectShippingAddressUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final AddressPort addressPort;
    private final EventPublisher eventPublisher;

    public SelectShippingAddressUseCase(CheckoutRepository checkoutRepository,
                                         AddressPort addressPort, EventPublisher eventPublisher) {
        this.checkoutRepository = checkoutRepository;
        this.addressPort = addressPort;
        this.eventPublisher = eventPublisher;
    }

    public Mono<CheckoutResult> execute(SelectShippingAddressCommand command) {
        var checkoutId = CheckoutId.fromUUID(command.checkoutId());

        return checkoutRepository.findById(checkoutId)
            .switchIfEmpty(Mono.error(new CheckoutDomainException(
                CheckoutErrorCode.CHECKOUT_NOT_FOUND, "Checkout not found: " + command.checkoutId())))
            .flatMap(checkout -> {
                if (!checkout.getCustomerId().equals(command.customerId())) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER,
                        "Checkout does not belong to customer: " + command.customerId()));
                }
                return addressPort.addressBelongsToCustomer(command.addressId(), command.customerId())
                    .flatMap(belongs -> {
                        if (!belongs) {
                            return Mono.error(new CheckoutDomainException(
                                CheckoutErrorCode.MISSING_SHIPPING_ADDRESS,
                                "Address does not belong to customer: " + command.addressId()));
                        }
                        checkout.selectShippingAddress(command.addressId());
                        return checkoutRepository.save(checkout)
                            .doOnSuccess(v -> {
                                checkout.getDomainEvents().forEach(eventPublisher::publish);
                                checkout.clearDomainEvents();
                                log.info("Shipping address {} selected for checkout {}", command.addressId(), checkoutId.value());
                            })
                            .thenReturn(CheckoutResultMapper.toResult(checkout));
                    });
            });
    }
}
