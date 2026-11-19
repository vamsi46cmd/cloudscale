package io.cloudscale;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis read cache. Keys are namespaced and tenant-scoped ("cs:v1:ws:{workspaceId}:..."), so one tenant's entries can
 * never be addressed with another tenant's id. Only non-sensitive read models are cached; authorization decisions are
 * always re-read from PostgreSQL. If Redis fails, reads fall back to the database (fail-open) and staleness is bounded by the TTL.
 */
@Component
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Props props;
    private final RedisCircuit circuit;
    private final Counter hit;
    private final Counter miss;
    private final Counter error;

    public CacheService(StringRedisTemplate redis, ObjectMapper mapper, Props props, RedisCircuit circuit, MeterRegistry registry) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
        this.circuit = circuit;
        this.hit = registry.counter("cloudscale.cache.requests", "result", "hit");
        this.miss = registry.counter("cloudscale.cache.requests", "result", "miss");
        this.error = registry.counter("cloudscale.cache.requests", "result", "error");
    }

    public static String workspaceKey(UUID ws) {
        return "cs:v1:ws:" + ws + ":summary";
    }

    public static String projectKey(UUID ws, UUID project) {
        return "cs:v1:ws:" + ws + ":project:" + project;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (!props.cache().enabled() || circuit.isOpen()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                miss.increment();
                return Optional.empty();
            }
            hit.increment();
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            error.increment();
            circuit.failure();
            log.warn("Cache read failed, falling back to database: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, Object value) {
        if (!props.cache().enabled() || circuit.isOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), props.cache().ttl());
        } catch (Exception e) {
            error.increment();
            circuit.failure();
            log.warn("Cache write failed: {}", e.toString());
        }
    }

    /** Evicts once the surrounding transaction has committed, so a concurrent reader cannot re-cache the old row. */
    public void evictAfterCommit(String... keys) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(keys); }
            });
        } else {
            evict(keys);
        }
    }

    public void evict(String... keys) {
        if (!props.cache().enabled()) {
            return;
        }
        try {
            redis.delete(List.of(keys));
        } catch (Exception e) {
            error.increment();
            circuit.failure();
            log.warn("Cache eviction failed (entries expire by TTL): {}", e.toString());
        }
    }

    /** Removes every cached entry of one workspace (used when the workspace is deleted). */
    public void evictWorkspaceAfterCommit(UUID ws) {
        Runnable r = () -> {
            try (var cursor = redis.scan(ScanOptions.scanOptions().match("cs:v1:ws:" + ws + ":*").count(100).build())) {
                List<String> keys = new ArrayList<>();
                cursor.forEachRemaining(keys::add);
                if (!keys.isEmpty()) {
                    redis.delete(keys);
                }
            } catch (Exception e) {
                error.increment();
                log.warn("Workspace cache purge failed (entries expire by TTL): {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
