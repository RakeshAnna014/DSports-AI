package com.dsports.returns.infrastructure.persistence;

import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.returns.domain.model.ReturnRequest;
import com.dsports.returns.domain.model.ReturnStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ReturnR2dbcRepositoryAdapter implements ReturnRepository {

    private final SpringR2dbcReturnRepository springRepository;
    private final DatabaseClient databaseClient;

    public ReturnR2dbcRepositoryAdapter(SpringR2dbcReturnRepository springRepository, DatabaseClient databaseClient) {
        this.springRepository = springRepository;
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<ReturnRequest> findById(UUID id) {
        return springRepository.findById(id).map(ReturnR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<ReturnRequest> findByUserId(UUID userId) {
        return springRepository.findByUserIdOrderByCreatedAtDesc(userId).map(ReturnR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<ReturnRequest> findAll() {
        return springRepository.findAllByOrderByCreatedAtDesc().map(ReturnR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<ReturnRequest> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey) {
        return springRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            .map(ReturnR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Integer> sumHeldQuantity(UUID orderId, UUID productId) {
        return databaseClient.sql("""
                SELECT COALESCE(SUM(quantity), 0) AS held FROM return_requests
                 WHERE order_id = :orderId AND product_id = :productId AND status IN ('REQUESTED', 'APPROVED')
                """)
            .bind("orderId", orderId)
            .bind("productId", productId)
            .map((row, meta) -> row.get("held", Number.class).intValue())
            .one()
            .defaultIfEmpty(0);
    }

    @Override
    public Mono<Void> lockOrderLine(UUID orderId, UUID productId) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "return:" + orderId + ":" + productId)
            .fetch().all()
            .then();
    }

    @Override
    public Mono<Void> save(ReturnRequest request) {
        return springRepository.save(toEntity(request))
            .doOnNext(saved -> request.setVersion(saved.getVersion()))
            .onErrorMap(OptimisticLockingFailureException.class, e ->
                new ReturnsDomainException(ReturnsErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                    "Return " + request.getId() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e ->
                new ReturnsDomainException(ReturnsErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "A return request with this Idempotency-Key already exists"))
            .then();
    }

    private static ReturnRequestEntity toEntity(ReturnRequest r) {
        var e = new ReturnRequestEntity();
        e.setId(r.getId());
        e.setOrderId(r.getOrderId());
        e.setUserId(r.getUserId());
        e.setProductId(r.getProductId());
        e.setProductName(r.getProductName());
        e.setQuantity(r.getQuantity());
        e.setReason(r.getReason());
        e.setIdempotencyKey(r.getIdempotencyKey());
        e.setStatus(r.getStatus().name());
        e.setDecisionNote(r.getDecisionNote());
        e.setVersion(r.getVersion());
        e.setCreatedAt(r.getCreatedAt());
        e.setUpdatedAt(r.getUpdatedAt());
        return e;
    }

    private static ReturnRequest toDomain(ReturnRequestEntity e) {
        return ReturnRequest.reconstitute(e.getId(), e.getOrderId(), e.getUserId(), e.getProductId(), e.getProductName(),
            e.getQuantity(), e.getReason(), e.getIdempotencyKey(), ReturnStatus.valueOf(e.getStatus()),
            e.getDecisionNote(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
