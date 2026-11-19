package io.cloudscale;

import org.springframework.stereotype.Component;

/**
 * Tiny breaker shared by the cache and the rate limiter: after a Redis failure, skip Redis for 5 seconds so a dead
 * Redis costs one timeout per window instead of one timeout per request.
 */
@Component
public class RedisCircuit {
    private static final long OPEN_MS = 5_000;
    private volatile long openUntil;

    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    public void failure() {
        openUntil = System.currentTimeMillis() + OPEN_MS;
    }
}
