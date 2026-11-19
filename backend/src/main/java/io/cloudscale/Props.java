package io.cloudscale;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cloudscale")
public record Props(Jwt jwt, Cache cache, RateLimit ratelimit, Jobs jobs) {
    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl) {}

    public record Cache(boolean enabled, Duration ttl) {}

    public record Bucket(int capacity, double refillPerSecond) {}

    public record RateLimit(boolean enabled, boolean trustForwardHeaders, Bucket auth, Bucket api) {}

    public record Jobs(boolean workerEnabled, int concurrency, Duration pollInterval, Duration lease,
                       int maxAttempts, Duration backoffBase, boolean testHooks, Duration shutdownGrace,
                       int maxCsvChars) {}
}
