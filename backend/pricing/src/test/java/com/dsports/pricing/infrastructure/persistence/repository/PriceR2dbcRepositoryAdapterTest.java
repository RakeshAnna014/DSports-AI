package com.dsports.pricing.infrastructure.persistence.repository;

import com.dsports.pricing.application.port.EventPublisher;
import com.dsports.pricing.domain.model.*;
import com.dsports.pricing.infrastructure.persistence.entity.PriceEntity;
import com.dsports.pricing.infrastructure.persistence.mapper.PriceEntityMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class PriceR2dbcRepositoryAdapterTest {

    private static PostgreSQLContainer<?> postgres;
    private static PriceR2dbcRepositoryAdapter repository;
    private static DatabaseClient databaseClient;

    @BeforeAll
    static void setUp() {
        try {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();
        } catch (Exception e) {
            assumeTrue(false, "Docker is required for this test: " + e.getMessage());
            return;
        }

        var jdbcUrl = postgres.getJdbcUrl();

        Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var r2dbcUrl = "r2dbc:postgresql://" + postgres.getUsername() + ":" + postgres.getPassword()
                + "@" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + postgres.getDatabaseName();

        ConnectionFactory connectionFactory = ConnectionFactories.get(
                ConnectionFactoryOptions.parse(r2dbcUrl));

        databaseClient = DatabaseClient.create(connectionFactory);

        var rxtx = TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(connectionFactory));

        var mapper = new PriceEntityMapper();

        var springRepository = new SpringR2dbcPriceRepository() {
            @Override
            public <S extends PriceEntity> Mono<S> save(S entity) {
                return databaseClient.sql("INSERT INTO prices (id, product_id, mrp, selling_price, currency, " +
                                "effective_from, effective_to, status, version, created_at, updated_at) " +
                                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) " +
                                "ON CONFLICT (id) DO UPDATE SET mrp = EXCLUDED.mrp, " +
                                "selling_price = EXCLUDED.selling_price, currency = EXCLUDED.currency, " +
                                "effective_from = EXCLUDED.effective_from, effective_to = EXCLUDED.effective_to, " +
                                "status = EXCLUDED.status, version = EXCLUDED.version + 1, " +
                                "updated_at = EXCLUDED.updated_at")
                        .bind("$1", entity.getId())
                        .bind("$2", entity.getProductId())
                        .bind("$3", entity.getMrp())
                        .bind("$4", entity.getSellingPrice())
                        .bind("$5", entity.getCurrency())
                        .bind("$6", entity.getEffectiveFrom())
                        .bind("$7", entity.getEffectiveTo())
                        .bind("$8", entity.getStatus())
                        .bind("$9", entity.getVersion())
                        .bind("$10", entity.getCreatedAt())
                        .bind("$11", entity.getUpdatedAt())
                        .fetch()
                        .rowsUpdated()
                        .thenReturn(entity);
            }

            @Override
            public <S extends PriceEntity> Flux<S> saveAll(Iterable<S> entities) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Flux<S> saveAll(org.reactivestreams.Publisher<S> entityStream) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<PriceEntity> findById(UUID id) {
                return databaseClient.sql("SELECT * FROM prices WHERE id = $1")
                        .bind("$1", id)
                        .map((row, metadata) -> mapRow(row))
                        .one();
            }

            @Override
            public Mono<PriceEntity> findById(org.reactivestreams.Publisher<UUID> id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Boolean> existsById(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Boolean> existsById(org.reactivestreams.Publisher<UUID> id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<PriceEntity> findAll() {
                return databaseClient.sql("SELECT * FROM prices ORDER BY created_at DESC")
                        .map((row, metadata) -> mapRow(row))
                        .all();
            }

            @Override
            public Flux<PriceEntity> findAllById(Iterable<UUID> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<PriceEntity> findAllById(org.reactivestreams.Publisher<UUID> idStream) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Long> count() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Void> deleteById(UUID id) {
                return databaseClient.sql("DELETE FROM prices WHERE id = $1")
                        .bind("$1", id)
                        .then();
            }

            @Override
            public Mono<Void> deleteById(org.reactivestreams.Publisher<UUID> id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Void> delete(PriceEntity entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Mono<Void> deleteAll(Iterable<? extends PriceEntity> entities) {
                return databaseClient.sql("DELETE FROM prices").then();
            }

            @Override
            public Mono<Void> deleteAll(org.reactivestreams.Publisher<? extends PriceEntity> entities) {
                return databaseClient.sql("DELETE FROM prices").then();
            }

            @Override
            public Mono<Void> deleteAll() {
                return databaseClient.sql("DELETE FROM prices").then();
            }

            @Override
            public Mono<Void> deleteAllById(Iterable<? extends UUID> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<PriceEntity> findAll(Sort sort) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Mono<S> findOne(Example<S> example) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Flux<S> findAll(Example<S> example) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Flux<S> findAll(Example<S> example, Sort sort) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Mono<Long> count(Example<S> example) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity> Mono<Boolean> exists(Example<S> example) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S extends PriceEntity, R, P extends org.reactivestreams.Publisher<R>> P findBy(
                    Example<S> example,
                    java.util.function.Function<FluentQuery.ReactiveFluentQuery<S>, P> queryFunction) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<PriceEntity> findByProductIdOrderByCreatedAtDesc(UUID productId) {
                return databaseClient.sql("SELECT * FROM prices WHERE product_id = $1 ORDER BY created_at DESC")
                        .bind("$1", productId)
                        .map((row, metadata) -> mapRow(row))
                        .all();
            }

            @Override
            public Mono<Boolean> existsByProductIdAndCurrencyAndStatus(UUID productId, String currency, String status) {
                return databaseClient.sql("SELECT COUNT(*) as cnt FROM prices WHERE product_id = $1 " +
                                "AND currency = $2 AND status = $3")
                        .bind("$1", productId)
                        .bind("$2", currency)
                        .bind("$3", status)
                        .map((row, metadata) -> row.get("cnt", Long.class))
                        .one()
                        .map(count -> count != null && count > 0);
            }

            @Override
            public Mono<Integer> deactivateActivePrices(UUID productId, String currency, UUID excludeId) {
                return databaseClient.sql("UPDATE prices SET status = 'ARCHIVED', updated_at = NOW() " +
                                "WHERE product_id = $1 AND currency = $2 " +
                                "AND status = 'ACTIVE' AND id != $3")
                        .bind("$1", productId)
                        .bind("$2", currency)
                        .bind("$3", excludeId)
                        .fetch()
                        .rowsUpdated()
                        .map(Long::intValue);
            }
        };

        var eventPublisher = mock(EventPublisher.class);
        repository = new PriceR2dbcRepositoryAdapter(springRepository, mapper, eventPublisher, rxtx);
    }

    private static PriceEntity mapRow(io.r2dbc.spi.Row row) {
        var entity = new PriceEntity();
        entity.setId(row.get("id", UUID.class));
        entity.setProductId(row.get("product_id", UUID.class));
        entity.setMrp(row.get("mrp", BigDecimal.class));
        entity.setSellingPrice(row.get("selling_price", BigDecimal.class));
        entity.setCurrency(row.get("currency", String.class));
        entity.setEffectiveFrom(row.get("effective_from", Instant.class));
        entity.setEffectiveTo(row.get("effective_to", Instant.class));
        entity.setStatus(row.get("status", String.class));
        entity.setVersion(row.get("version", Integer.class));
        entity.setCreatedAt(row.get("created_at", Instant.class));
        entity.setUpdatedAt(row.get("updated_at", Instant.class));
        return entity;
    }

    @BeforeEach
    void cleanDb() {
        if (databaseClient != null) {
            databaseClient.sql("DELETE FROM prices").then().block();
        }
    }

    @Test
    void shouldSaveAndFindPriceById() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());

        StepVerifier.create(repository.save(price))
                .verifyComplete();

        StepVerifier.create(repository.findById(price.getId()))
                .assertNext(found -> {
                    assertThat(found.getId()).isEqualTo(price.getId());
                    assertThat(found.getMrp().value()).isEqualByComparingTo(BigDecimal.valueOf(200));
                    assertThat(found.getSellingPrice().value()).isEqualByComparingTo(BigDecimal.valueOf(150));
                    assertThat(found.getCurrency().code()).isEqualTo("INR");
                    assertThat(found.getStatus()).isEqualTo(PriceStatus.DRAFT);
                })
                .verifyComplete();
    }

    @Test
    void shouldFindByProductId() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price1 = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        var price2 = Price.create(productId, Money.from(300), Money.from(250),
                Currency.from("USD"), EffectiveDate.immediate());

        repository.save(price1).block();
        repository.save(price2).block();

        StepVerifier.create(repository.findByProductId(productId))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldCheckExistingActivePrice() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.activate();

        repository.save(price).block();

        StepVerifier.create(repository.existsByProductIdAndCurrencyAndStatus(
                        productId, Currency.from("INR"), PriceStatus.ACTIVE))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldReturnFalseWhenNoActivePriceExists() {
        var productId = ProductId.fromUUID(UUID.randomUUID());

        StepVerifier.create(repository.existsByProductIdAndCurrencyAndStatus(
                        productId, Currency.from("INR"), PriceStatus.ACTIVE))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldDeactivateActivePrices() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price1 = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price1.activate();
        var price2 = Price.create(productId, Money.from(250), Money.from(200),
                Currency.from("INR"), EffectiveDate.immediate());
        price2.activate();

        repository.save(price1).block();
        repository.save(price2).block();

        StepVerifier.create(repository.deactivateActivePrices(
                        productId, Currency.from("INR"), price2.getId()))
                .verifyComplete();

        StepVerifier.create(repository.findById(price1.getId()))
                .assertNext(found -> assertThat(found.getStatus()).isEqualTo(PriceStatus.ARCHIVED))
                .verifyComplete();

        StepVerifier.create(repository.findById(price2.getId()))
                .assertNext(found -> assertThat(found.getStatus()).isEqualTo(PriceStatus.ACTIVE))
                .verifyComplete();
    }

    @Test
    void shouldFindAllPrices() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        repository.save(price).block();

        StepVerifier.create(repository.findAll())
                .expectNextCount(1)
                .verifyComplete();
    }
}
