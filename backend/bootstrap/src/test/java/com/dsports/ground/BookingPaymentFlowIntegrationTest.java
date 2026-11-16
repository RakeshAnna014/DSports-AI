package com.dsports.ground;

import com.dsports.DSportsApplication;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.CompleteEndedBookingsUseCase;
import com.dsports.ground.application.usecase.ExpireHeldBookingsUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.application.usecase.ManageGroundsUseCase;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.PitchType;
import com.dsports.payment.application.payment.command.CapturePaymentCommand;
import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.usecase.CapturePaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.infrastructure.payment.gateway.MockPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Booking + payment + expiry + outbox, end to end on the real application (every module, every migration from scratch) against a throwaway
 * PostgreSQL. The payment gateway is the real mock gateway wrapped so a test can hold a capture "in flight" while it does other things.
 */
@Tag("integration")
@SpringBootTest(classes = DSportsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.ground.expiry-initial-delay=PT24H", "app.orders.expiry-initial-delay=PT24H",
    "app.outbox.initial-delay=0", "app.outbox.poll-interval=300"})
@Import(BookingPaymentFlowIntegrationTest.GatedGatewayConfig.class)
class BookingPaymentFlowIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /** The real mock gateway, except that a test can make {@code capturePayment} wait after it has been called, i.e. while the payment is PROCESSING. */
    static final class GatedGateway implements PaymentGateway {
        private final PaymentGateway delegate;
        volatile CountDownLatch captureStarted;
        volatile CountDownLatch captureRelease;

        GatedGateway(PaymentGateway delegate) {
            this.delegate = delegate;
        }

        void arm() {
            captureStarted = new CountDownLatch(1);
            captureRelease = new CountDownLatch(1);
        }

        @Override
        public Mono<GatewayResult> initiatePayment(InitiatePaymentRequest request) {
            return delegate.initiatePayment(request);
        }

        @Override
        public Mono<GatewayResult> capturePayment(String gatewayReference) {
            var started = captureStarted;
            var release = captureRelease;
            if (started == null) {
                return delegate.capturePayment(gatewayReference);
            }
            return Mono.fromCallable(() -> {
                started.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the gateway");
                }
                return gatewayReference;
            }).subscribeOn(Schedulers.boundedElastic()).flatMap(delegate::capturePayment);
        }

        @Override
        public Mono<GatewayResult> cancelPayment(String gatewayReference) {
            return delegate.cancelPayment(gatewayReference);
        }

        @Override
        public Mono<GatewayResult> refundPayment(String gatewayReference, Money amount, String idempotencyKey) {
            return delegate.refundPayment(gatewayReference, amount, idempotencyKey);
        }

        @Override
        public Mono<GatewayStatus> getPaymentStatus(String gatewayReference) {
            return delegate.getPaymentStatus(gatewayReference);
        }
    }

    @TestConfiguration
    static class GatedGatewayConfig {
        @Bean
        @Primary
        GatedGateway gatedGateway(MockPaymentGateway mock) {
            return new GatedGateway(mock);
        }
    }

    @Autowired private ManageGroundsUseCase manageGrounds;
    @Autowired private HoldBookingUseCase hold;
    @Autowired private ExpireHeldBookingsUseCase expireHeld;
    @Autowired private CancelBookingUseCase cancelBooking;
    @Autowired private CompleteEndedBookingsUseCase completeEnded;
    @Autowired private ListBookingsUseCase listBookings;
    @Autowired private CreatePaymentUseCase createPayment;
    @Autowired private CapturePaymentUseCase capturePayment;
    @Autowired private DatabaseClient db;
    @Autowired private GatedGateway gateway;

    private final UUID owner = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID otherCustomer = UUID.randomUUID();
    private UUID groundId;
    private int nextHour;

    @BeforeEach
    void freshGround() {
        gateway.captureStarted = null;
        gateway.captureRelease = null;
        var spec = new Ground.Spec("Flow Turf " + UUID.randomUUID(), "1 Test Road", "Pune", null, null, PitchType.TURF, List.of(), List.of(), LocalTime.of(6, 0),
            LocalTime.of(22, 0), IST, 60, new BigDecimal("1000"), new BigDecimal("1500"), null, null, null);
        groundId = manageGrounds.create(owner, spec).block().ground().id();
        nextHour = 7;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static LocalDate day() {
        return LocalDate.now(IST).plusDays(5);
    }

    private HoldBookingUseCase.Command slot(UUID user, int hour) {
        return new HoldBookingUseCase.Command(user, groundId, day().atTime(hour, 0).atZone(IST).toInstant(), day().atTime(hour + 1, 0).atZone(IST).toInstant(),
            UUID.randomUUID().toString());
    }

    private UUID heldBooking(UUID user) {
        return hold.execute(slot(user, nextHour++)).block().booking().id();
    }

    private BigDecimal price(UUID bookingId) {
        return db.sql("SELECT price FROM ground_bookings WHERE id = :id").bind("id", bookingId).map((r, m) -> r.get("price", BigDecimal.class)).one().block();
    }

    private CreatePaymentCommand paymentCommand(UUID user, UUID bookingId, BigDecimal amount) {
        return new CreatePaymentCommand(user, bookingId, amount, "INR", "UPI", "MOCK");
    }

    private UUID authorised(UUID user, UUID bookingId) {
        var payment = createPayment.execute(paymentCommand(user, bookingId, price(bookingId))).block();
        assertThat(payment.status()).isEqualTo("AUTHORIZED");
        return payment.id();
    }

    private String bookingStatus(UUID id) {
        return db.sql("SELECT status FROM ground_bookings WHERE id = :id").bind("id", id).map((r, m) -> r.get("status", String.class)).one().block();
    }

    private UUID bookingPaymentId(UUID id) {
        return db.sql("SELECT payment_id FROM ground_bookings WHERE id = :id").bind("id", id).map((r, m) -> r.get("payment_id", UUID.class)).one().block();
    }

    private String paymentStatus(UUID id) {
        return db.sql("SELECT status FROM payments WHERE id = :id").bind("id", id).map((r, m) -> r.get("status", String.class)).one().block();
    }

    private void lapseTheHold(UUID bookingId) {
        db.sql("UPDATE ground_bookings SET held_until = now() - interval '1 minute' WHERE id = :id").bind("id", bookingId).fetch().rowsUpdated().block();
    }

    private long count(String sql, Object... binds) {
        var spec = db.sql(sql);
        for (int i = 0; i < binds.length; i++) {
            spec = spec.bind(i, binds[i]);
        }
        return spec.map((r, m) -> r.get(0, Long.class)).one().block();
    }

    private static void await(Supplier<Boolean> condition, String what) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private CompletableFuture<?> captureInBackground(UUID user, UUID paymentId) {
        return CompletableFuture.supplyAsync(() -> capturePayment.execute(new CapturePaymentCommand(paymentId, user)).block());
    }

    private static void assertPaymentCode(Throwable error, PaymentErrorCode code) {
        var cause = error instanceof ExecutionException ? error.getCause() : error;
        assertThat(cause).isInstanceOfSatisfying(PaymentDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // ---- the happy path ----------------------------------------------------------------------------------------

    @Test
    void aHeldBookingIsPaidAndConfirmedAndTheCustomerIsNotified() {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);

        var captured = capturePayment.execute(new CapturePaymentCommand(paymentId, customer)).block();

        assertThat(captured.status()).isEqualTo("SUCCESS");
        assertThat(captured.amount()).isEqualByComparingTo(price(booking));
        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(bookingPaymentId(booking)).isEqualTo(paymentId);
        await(() -> count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = 'BOOKING_CONFIRMED'", customer) == 1, "the booking-confirmed notification");
        await(() -> count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = 'PAYMENT_SUCCESS'", customer) == 1, "the payment notification");
    }

    @Test
    void paymentIsRefusedForSomeoneElsesBookingAndTheAmountIsAlwaysTheBookingsOwn() {
        var booking = heldBooking(customer);
        var amount = price(booking);

        assertThatThrownBy(() -> createPayment.execute(paymentCommand(otherCustomer, booking, amount)).block())
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.BOOKING_NOT_OWNED_BY_USER));
        assertThatThrownBy(() -> createPayment.execute(paymentCommand(customer, booking, amount.subtract(BigDecimal.ONE))).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.AMOUNT_MISMATCH));
        assertThatThrownBy(() -> createPayment.execute(paymentCommand(customer, booking, BigDecimal.ONE)).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.AMOUNT_MISMATCH));
        assertThat(count("SELECT COUNT(*) FROM payments WHERE order_id = $1", booking)).isZero();

        var payment = createPayment.execute(paymentCommand(customer, booking, amount)).block();
        assertThat(payment.amount()).isEqualByComparingTo(amount);
    }

    @Test
    void aBookingWhoseHoldHasLapsedCannotBeAuthorisedAndAnUnknownIdIsNotFound() {
        var booking = heldBooking(customer);
        lapseTheHold(booking);

        assertThatThrownBy(() -> createPayment.execute(paymentCommand(customer, booking, price(booking))).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.ORDER_NOT_PAYABLE));
        assertThatThrownBy(() -> createPayment.execute(paymentCommand(customer, UUID.randomUUID(), BigDecimal.TEN)).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void aBookingThatIsAlreadyPaidCannotBePaidTwice() {
        var booking = heldBooking(customer);
        capturePayment.execute(new CapturePaymentCommand(authorised(customer, booking), customer)).block();

        assertThatThrownBy(() -> createPayment.execute(paymentCommand(customer, booking, price(booking))).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.ORDER_ALREADY_PAID));
    }

    // ---- hold expiry vs payment --------------------------------------------------------------------------------

    @Test
    void expiringALapsedHoldVoidsItsUnpaidPaymentAndFreesTheSlot() {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);
        lapseTheHold(booking);

        assertThat(expireHeld.execute().block()).isGreaterThanOrEqualTo(1);

        assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> capturePayment.execute(new CapturePaymentCommand(paymentId, customer)).block())
            .satisfies(e -> assertPaymentCode(e, PaymentErrorCode.INVALID_STATUS_TRANSITION));
        var reHeld = hold.execute(new HoldBookingUseCase.Command(otherCustomer, groundId, day().atTime(7, 0).atZone(IST).toInstant(),
            day().atTime(8, 0).atZone(IST).toInstant(), UUID.randomUUID().toString())).block();
        assertThat(reHeld.booking().status()).isEqualTo("HELD");
    }

    @Test
    void theExpiryJobSkipsABookingWhosePaymentIsBeingCapturedAndTheCaptureStillConfirmsIt() throws Exception {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);
        gateway.arm();
        var capture = captureInBackground(customer, paymentId);
        assertThat(gateway.captureStarted.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");
        lapseTheHold(booking);

        var expired = expireHeld.execute().block();

        assertThat(expired).isZero();
        assertThat(bookingStatus(booking)).isEqualTo("HELD");
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");

        gateway.captureRelease.countDown();
        capture.get(20, TimeUnit.SECONDS);
        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCESS");
        assertThat(bookingPaymentId(booking)).isEqualTo(paymentId);
        assertThat(expireHeld.execute().block()).isZero();
        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
    }

    @Test
    void ifTheBookingIsNoLongerHeldWhenTheCaptureCompletesThePaymentIsRefundedInFullAndTheCaptureFailsClearly() throws Exception {
        var booking = heldBooking(customer);
        var amount = price(booking);
        var paymentId = authorised(customer, booking);
        gateway.arm();
        var capture = captureInBackground(customer, paymentId);
        assertThat(gateway.captureStarted.await(15, TimeUnit.SECONDS)).isTrue();

        // Simulate every guard failing: the booking is expired behind the payment's back while the money is moving.
        db.sql("UPDATE ground_bookings SET status = 'EXPIRED' WHERE id = :id").bind("id", booking).fetch().rowsUpdated().block();
        gateway.captureRelease.countDown();

        assertThatThrownBy(() -> capture.get(20, TimeUnit.SECONDS)).satisfies(e -> assertPaymentCode(e, PaymentErrorCode.PAID_ITEM_NO_LONGER_AVAILABLE));

        assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE id = $1 AND payment_id IS NULL", booking)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = $1 AND amount = $2", paymentId, amount)).isEqualTo(1);
        await(() -> "COMPLETED".equals(db.sql("SELECT status FROM refunds WHERE payment_id = :p").bind("p", paymentId).map((r, m) -> r.get("status", String.class)).one().block()),
            "the automatic refund to complete");
        await(() -> "REFUNDED".equals(paymentStatus(paymentId)), "the payment to be fully refunded");
        await(() -> count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = 'BOOKING_CANCELLED'", customer) == 1, "the customer to be told");
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type IN ('PAYMENT_SUCCESS', 'BOOKING_CONFIRMED')", customer)).isZero();
    }

    @Test
    void aBookingCancelledBehindThePaymentsBackIsAlsoRefundedNotConfirmed() throws Exception {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);
        gateway.arm();
        var capture = captureInBackground(customer, paymentId);
        assertThat(gateway.captureStarted.await(15, TimeUnit.SECONDS)).isTrue();
        db.sql("UPDATE ground_bookings SET status = 'CANCELLED', cancelled_by = 'OWNER', cancelled_at = now() WHERE id = :id").bind("id", booking).fetch().rowsUpdated().block();
        gateway.captureRelease.countDown();

        assertThatThrownBy(() -> capture.get(20, TimeUnit.SECONDS)).satisfies(e -> assertPaymentCode(e, PaymentErrorCode.PAID_ITEM_NO_LONGER_AVAILABLE));

        assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
        await(() -> "REFUNDED".equals(paymentStatus(paymentId)), "the payment to be refunded");
    }

    // ---- cancellation ------------------------------------------------------------------------------------------

    private UUID paid(UUID user, UUID bookingId) {
        var paymentId = authorised(user, bookingId);
        capturePayment.execute(new CapturePaymentCommand(paymentId, user)).block();
        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        return paymentId;
    }

    @Test
    void aCustomerCancellingAPaidBookingFilesARefundRequestAwaitingApprovalAndIsNotified() {
        var booking = heldBooking(customer);
        var paymentId = paid(customer, booking);

        var result = cancelBooking.execute(new CancelBookingUseCase.Command(customer, booking, CancelledBy.CUSTOMER)).block();

        assertThat(result.refund()).isEqualTo(CancelBookingUseCase.Refund.REQUESTED);
        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = $1 AND status = 'REQUESTED' AND amount = $2 AND requested_by = $3", paymentId, price(booking), customer))
            .isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCESS");
        await(() -> count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = 'BOOKING_CANCELLED' AND message LIKE '%awaiting approval%'", customer) == 1,
            "the cancellation notification");
        assertThat(listBookings.mine(customer).block()).extracting("status").contains("CANCELLED");
    }

    @Test
    void anOwnerCancellingAPaidBookingRefundsItInFullAutomaticallyAndTheCustomerIsNotified() {
        var booking = heldBooking(customer);
        var paymentId = paid(customer, booking);

        var result = cancelBooking.execute(new CancelBookingUseCase.Command(owner, booking, CancelledBy.OWNER)).block();

        assertThat(result.refund()).isEqualTo(CancelBookingUseCase.Refund.STARTED);
        assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
        await(() -> "COMPLETED".equals(db.sql("SELECT status FROM refunds WHERE payment_id = :p").bind("p", paymentId).map((r, m) -> r.get("status", String.class)).one().block()),
            "the automatic refund to complete");
        await(() -> "REFUNDED".equals(paymentStatus(paymentId)), "the payment to be fully refunded");
        await(() -> count("SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND type = 'BOOKING_CANCELLED' AND message LIKE '%automatically%'", customer) == 1,
            "the cancellation notification");
        assertThat(listBookings.atGround(owner, groundId, day()).block()).hasSize(1);
    }

    @Test
    void cancellingAHeldBookingVoidsItsAuthorisedPaymentAndFreesTheSlot() {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);

        cancelBooking.execute(new CancelBookingUseCase.Command(customer, booking, CancelledBy.CUSTOMER)).block();

        assertThat(bookingStatus(booking)).isEqualTo("CANCELLED");
        assertThat(paymentStatus(paymentId)).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = $1", paymentId)).isZero();
        assertThat(hold.execute(slot(otherCustomer, 7)).block().booking().status()).isEqualTo("HELD");
    }

    @Test
    void aBookingBeingPaidCannotBeCancelledAndTheCaptureStillConfirmsIt() throws Exception {
        var booking = heldBooking(customer);
        var paymentId = authorised(customer, booking);
        gateway.arm();
        var capture = captureInBackground(customer, paymentId);
        assertThat(gateway.captureStarted.await(15, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> cancelBooking.execute(new CancelBookingUseCase.Command(customer, booking, CancelledBy.CUSTOMER)).block())
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.PAYMENT_IN_PROGRESS));
        assertThat(bookingStatus(booking)).isEqualTo("HELD");

        gateway.captureRelease.countDown();
        capture.get(20, TimeUnit.SECONDS);
        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCESS");
    }

    @Test
    void aSecondCancelIsRefusedAndFilesNoSecondRefund() {
        var booking = heldBooking(customer);
        var paymentId = paid(customer, booking);
        cancelBooking.execute(new CancelBookingUseCase.Command(customer, booking, CancelledBy.CUSTOMER)).block();

        assertThatThrownBy(() -> cancelBooking.execute(new CancelBookingUseCase.Command(customer, booking, CancelledBy.CUSTOMER)).block())
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id = $1", paymentId)).isEqualTo(1);
    }

    @Test
    void aConfirmedBookingThatEndedLongAgoIsCompletedByTheHousekeepingRun() {
        var booking = heldBooking(customer);
        paid(customer, booking);
        db.sql("UPDATE ground_bookings SET start_at = now() - interval '3 days', end_at = now() - interval '3 days' + interval '1 hour' WHERE id = :id")
            .bind("id", booking).fetch().rowsUpdated().block();

        assertThat(completeEnded.execute().block()).isGreaterThanOrEqualTo(1);

        assertThat(bookingStatus(booking)).isEqualTo("COMPLETED");
    }

    @Test
    void theSlotIsExclusiveEvenThroughThePaymentPath() {
        var first = hold.execute(slot(customer, 10)).block().booking();
        assertThatThrownBy(() -> hold.execute(slot(otherCustomer, 10)).block()).isInstanceOfSatisfying(GroundDomainException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.SLOT_UNAVAILABLE));
        capturePayment.execute(new CapturePaymentCommand(authorised(customer, first.id()), customer)).block();
        assertThatThrownBy(() -> hold.execute(slot(otherCustomer, 10)).block()).isInstanceOfSatisfying(GroundDomainException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.SLOT_UNAVAILABLE));
        assertThat(Instant.now()).isNotNull();
    }
}
