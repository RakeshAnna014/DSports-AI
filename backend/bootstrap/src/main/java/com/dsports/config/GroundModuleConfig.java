package com.dsports.config;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.usecase.BookingPaymentUseCase;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.CompleteEndedBookingsUseCase;
import com.dsports.ground.application.usecase.ExpireHeldBookingsUseCase;
import com.dsports.ground.application.usecase.GetBookingUseCase;
import com.dsports.ground.application.usecase.GetGroundSlotsUseCase;
import com.dsports.ground.application.usecase.GetGroundUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageGroundsUseCase;
import com.dsports.ground.application.usecase.SearchGroundsUseCase;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.CancellationPolicy;
import com.dsports.ground.infrastructure.persistence.BlackoutR2dbcRepositoryAdapter;
import com.dsports.ground.infrastructure.persistence.BookingR2dbcRepositoryAdapter;
import com.dsports.ground.infrastructure.persistence.GroundR2dbcRepositoryAdapter;
import com.dsports.ground.infrastructure.persistence.SpringR2dbcBookingRepository;
import com.dsports.ground.infrastructure.persistence.SpringR2dbcGroundRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.refund.RequestRefundUseCase;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Wires the ground-booking module. The module owns its tables; the only places it touches other modules (payment, notifications) are
 * ports connected here and in {@link PaymentModuleConfig}. {@code app.ground.*} holds every tunable rule.
 */
@Configuration
public class GroundModuleConfig {

    /** The requester recorded on refunds the system starts by itself (there is no human requester). */
    private static final UUID SYSTEM_REQUESTER = new UUID(0L, 0L);

    @Bean
    public GroundRepository groundRepository(SpringR2dbcGroundRepository springRepository) {
        return new GroundR2dbcRepositoryAdapter(springRepository);
    }

    @Bean
    public BlackoutRepository blackoutRepository(DatabaseClient databaseClient, R2dbcEntityTemplate template) {
        return new BlackoutR2dbcRepositoryAdapter(databaseClient, template);
    }

    @Bean
    public BookingRepository bookingRepository(SpringR2dbcBookingRepository springRepository, DatabaseClient databaseClient) {
        return new BookingR2dbcRepositoryAdapter(springRepository, databaseClient);
    }

    @Bean
    public BookingPolicy bookingPolicy(@Value("${app.ground.min-lead:PT1H}") Duration minLead,
                                       @Value("${app.ground.max-ahead:P30D}") Duration maxAhead,
                                       @Value("${app.ground.max-slots:4}") int maxSlots) {
        return new BookingPolicy(minLead, maxAhead, maxSlots);
    }

    @Bean
    public GetGroundUseCase getGroundUseCase(GroundRepository groundRepository) {
        return new GetGroundUseCase(groundRepository);
    }

    @Bean
    public GetGroundSlotsUseCase getGroundSlotsUseCase(GroundRepository groundRepository, BookingRepository bookingRepository,
                                                       BlackoutRepository blackoutRepository, BookingPolicy bookingPolicy, Clock clock) {
        return new GetGroundSlotsUseCase(groundRepository, bookingRepository, blackoutRepository, bookingPolicy, clock);
    }

    @Bean
    public ManageGroundsUseCase manageGroundsUseCase(GroundRepository groundRepository, Clock clock) {
        return new ManageGroundsUseCase(groundRepository, clock);
    }

    @Bean
    public ManageBlackoutsUseCase manageBlackoutsUseCase(GroundRepository groundRepository, BookingRepository bookingRepository,
                                                         BlackoutRepository blackoutRepository, TransactionRunner transactionRunner, Clock clock) {
        return new ManageBlackoutsUseCase(groundRepository, bookingRepository, blackoutRepository, transactionRunner, clock);
    }

    @Bean
    public HoldBookingUseCase holdBookingUseCase(GroundRepository groundRepository, BookingRepository bookingRepository, BlackoutRepository blackoutRepository,
                                                 BookingPolicy bookingPolicy, TransactionRunner transactionRunner, Clock clock,
                                                 @Value("${app.ground.hold-duration:PT10M}") Duration holdDuration,
                                                 @Value("${app.ground.max-held-per-user:2}") int maxHeldPerUser) {
        return new HoldBookingUseCase(groundRepository, bookingRepository, blackoutRepository, bookingPolicy, holdDuration, maxHeldPerUser, transactionRunner, clock);
    }

    /**
     * The booking module's view of payments. Bookings reuse the payments table through {@code order_id} (a temporary shortcut, see ADR-017),
     * so their payments are found by the booking id. A captured payment that cannot be honoured gets an automatic, full refund: a refund request
     * recorded in the caller's transaction and paid out afterwards by the outbox.
     */
    @Bean
    public BookingPaymentPort bookingPaymentPort(PaymentRepository paymentRepository, OutboxWriter outboxWriter, RequestRefundUseCase requestRefundUseCase) {
        return new BookingPaymentPort() {
            @Override
            public Mono<Void> voidOpenPayments(UUID bookingId) {
                return paymentRepository.findByOrderId(bookingId)
                    .filter(payment -> payment.getStatus().isOpen())
                    .concatMap(payment -> {
                        if (payment.isCaptureInProgress()) {
                            return Mono.<Payment>error(new GroundDomainException(GroundErrorCode.PAYMENT_IN_PROGRESS,
                                "A payment for this booking is being captured; try again shortly"));
                        }
                        return CancelPaymentUseCase.voidPayment(payment, paymentRepository, outboxWriter).doOnNext(Payment::clearDomainEvents);
                    })
                    .then();
            }

            @Override
            public Mono<Boolean> hasCapturedPayment(UUID bookingId) {
                return paymentRepository.findByOrderId(bookingId).any(payment -> payment.getStatus().isCaptured());
            }

            @Override
            public Mono<Void> requestRefund(UUID paymentId, UUID requesterId, java.math.BigDecimal amount, String reason, String idempotencyKey) {
                return requestRefundUseCase.execute(new RequestRefundUseCase.Command(paymentId, requesterId, true, amount, reason, idempotencyKey)).then();
            }

            @Override
            public Mono<Void> refundCapturedPayment(UUID paymentId, String reason) {
                var command = new RequestRefundUseCase.Command(paymentId, SYSTEM_REQUESTER, false, null, reason, "booking-not-honoured:" + paymentId);
                return requestRefundUseCase.execute(command)
                    .flatMap(refund -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.PROCESS_REFUND, refund.getId(), Map.of())));
            }
        };
    }

    @Bean
    public BookingPaymentUseCase bookingPaymentUseCase(BookingRepository bookingRepository, GroundRepository groundRepository,
                                                       BookingPaymentPort bookingPaymentPort, OutboxWriter outboxWriter, Clock clock) {
        return new BookingPaymentUseCase(bookingRepository, groundRepository, bookingPaymentPort, outboxWriter, clock);
    }

    @Bean
    public ExpireHeldBookingsUseCase expireHeldBookingsUseCase(BookingRepository bookingRepository, BookingPaymentPort bookingPaymentPort,
                                                               TransactionRunner transactionRunner, Clock clock) {
        return new ExpireHeldBookingsUseCase(bookingRepository, bookingPaymentPort, transactionRunner, clock);
    }

    @Bean
    public CancellationPolicy cancellationPolicy(@Value("${app.ground.cancel.full-refund-before:PT24H}") Duration fullRefundBefore,
                                                 @Value("${app.ground.cancel.partial-refund-before:PT6H}") Duration partialRefundBefore,
                                                 @Value("${app.ground.cancel.partial-percent:50}") int partialPercent) {
        return new CancellationPolicy(fullRefundBefore, partialRefundBefore, partialPercent);
    }

    @Bean
    public CancelBookingUseCase cancelBookingUseCase(BookingRepository bookingRepository, GroundRepository groundRepository, BookingPaymentPort bookingPaymentPort,
                                                     CancellationPolicy cancellationPolicy, OutboxWriter outboxWriter, TransactionRunner transactionRunner, Clock clock) {
        return new CancelBookingUseCase(bookingRepository, groundRepository, bookingPaymentPort, cancellationPolicy, outboxWriter, transactionRunner, clock);
    }

    @Bean
    public ListBookingsUseCase listBookingsUseCase(BookingRepository bookingRepository, GroundRepository groundRepository, TransactionRunner transactionRunner, Clock clock) {
        return new ListBookingsUseCase(bookingRepository, groundRepository, transactionRunner, clock);
    }

    @Bean
    public CompleteEndedBookingsUseCase completeEndedBookingsUseCase(BookingRepository bookingRepository, TransactionRunner transactionRunner, Clock clock,
                                                                     @Value("${app.ground.complete-after:PT24H}") Duration completeAfter) {
        return new CompleteEndedBookingsUseCase(bookingRepository, transactionRunner, completeAfter, clock);
    }

    @Bean
    public GetBookingUseCase getBookingUseCase(BookingRepository bookingRepository, GroundRepository groundRepository) {
        return new GetBookingUseCase(bookingRepository, groundRepository);
    }

    @Bean
    public SearchGroundsUseCase searchGroundsUseCase(GroundRepository groundRepository, GetGroundSlotsUseCase getGroundSlotsUseCase) {
        return new SearchGroundsUseCase(groundRepository, getGroundSlotsUseCase);
    }
}
