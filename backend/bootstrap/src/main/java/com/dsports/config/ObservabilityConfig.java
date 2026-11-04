package com.dsports.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Bridges the correlation ID that {@link CorrelationIdFilter} puts into the Reactor context to the SLF4J MDC, so every
 * log line written while handling a request (on any thread) carries {@code [correlationId]}.
 */
@Configuration
public class ObservabilityConfig {

    @PostConstruct
    void propagateCorrelationIdToLogs() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            CorrelationIdFilter.MDC_KEY,
            () -> MDC.get(CorrelationIdFilter.MDC_KEY),
            value -> MDC.put(CorrelationIdFilter.MDC_KEY, value),
            () -> MDC.remove(CorrelationIdFilter.MDC_KEY));
        Hooks.enableAutomaticContextPropagation();
    }
}
