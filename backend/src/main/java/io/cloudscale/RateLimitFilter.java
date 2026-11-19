package io.cloudscale;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Distributed token-bucket rate limiter (state lives in Redis, executed atomically by a Lua script), so limits are
 * shared by all API instances. Auth endpoints are limited per client IP; everything else per authenticated user
 * (per IP when unauthenticated).
 * Redis outage: API traffic fails open (availability over limiting); auth endpoints fall back to a per-instance
 * window so credential stuffing is still throttled, just less precisely.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(load(), List.class);

    private final StringRedisTemplate redis;
    private final Props props;
    private final ObjectMapper mapper;
    private final RedisCircuit circuit;
    private final Counter limited;
    private final Counter redisErrors;
    private final Map<String, long[]> localWindows = new ConcurrentHashMap<>();

    public RateLimitFilter(StringRedisTemplate redis, Props props, ObjectMapper mapper, RedisCircuit circuit, MeterRegistry registry) {
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
        this.circuit = circuit;
        this.limited = registry.counter("cloudscale.ratelimit.limited");
        this.redisErrors = registry.counter("cloudscale.ratelimit.redis.errors");
    }

    private static String load() {
        try (var in = new ClassPathResource("ratelimit.lua").getInputStream()) {
            return new String(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("ratelimit.lua missing", e);
        }
    }

    private record Decision(boolean allowed, long retryAfterMs, long remaining) {}

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!props.ratelimit().enabled() || req.getRequestURI().startsWith("/actuator")) {
            chain.doFilter(req, res);
            return;
        }
        boolean auth = req.getRequestURI().startsWith("/api/v1/auth/");
        Props.Bucket bucket = auth ? props.ratelimit().auth() : props.ratelimit().api();
        String id = auth ? "ip:" + clientIp(req) : identity(req);
        String key = "cs:v1:rl:" + (auth ? "auth:" : "api:") + id;

        Decision d = decide(key, bucket, auth);
        if (!d.allowed()) {
            limited.increment();
            long seconds = Math.max(1, (d.retryAfterMs() + 999) / 1000);
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", Long.toString(seconds));
            res.setHeader("X-RateLimit-Remaining", "0");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), ApiExceptionHandler.ApiError.of(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded", req.getRequestURI(), null));
            return;
        }
        res.setHeader("X-RateLimit-Remaining", Long.toString(d.remaining()));
        chain.doFilter(req, res);
    }

    @SuppressWarnings("unchecked")
    private Decision decide(String key, Props.Bucket b, boolean auth) {
        if (!circuit.isOpen()) {
            try {
                List<Object> r = (List<Object>) redis.execute(SCRIPT, List.of(key),
                        Integer.toString(b.capacity()), Double.toString(b.refillPerSecond()));
                if (r != null && r.size() == 3) {
                    return new Decision(((Number) r.get(0)).longValue() == 1, ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue());
                }
            } catch (Exception e) {
                redisErrors.increment();
                circuit.failure();
                log.warn("Rate limiter Redis failure ({}): {}", auth ? "auth: local fallback" : "api: fail-open", e.toString());
            }
        }
        return auth ? localFallback(key, b) : new Decision(true, 0, -1);
    }

    private Decision localFallback(String key, Props.Bucket b) {
        if (localWindows.size() > 10_000) {
            localWindows.clear();
        }
        long now = System.currentTimeMillis();
        long[] w = localWindows.compute(key, (k, cur) -> {
            if (cur == null || now - cur[0] >= 60_000) {
                return new long[] {now, 1};
            }
            cur[1]++;
            return cur;
        });
        boolean ok = w[1] <= b.capacity();
        return new Decision(ok, Math.max(0, 60_000 - (now - w[0])), Math.max(0, b.capacity() - w[1]));
    }

    private String identity(HttpServletRequest req) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken jwt) {
            return "user:" + jwt.getToken().getSubject();
        }
        return "ip:" + clientIp(req);
    }

    private String clientIp(HttpServletRequest req) {
        if (props.ratelimit().trustForwardHeaders()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return req.getRemoteAddr();
    }
}
