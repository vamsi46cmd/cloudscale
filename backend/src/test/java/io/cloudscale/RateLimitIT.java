package io.cloudscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Proves the limit is shared through Redis, not held per instance: two separate application instances (separate Spring
 * contexts, separate ports) are hit alternately with the same client identity and together allow exactly `capacity` requests.
 */
class RateLimitIT {
    private static ConfigurableApplicationContext start(int capacity) {
        return new SpringApplication(CloudScaleApplication.class).run(
            "--server.port=0", "--management.server.port=-1", "--spring.main.banner-mode=off",
            "--spring.datasource.url=" + TestSupport.DB_URL,
            "--cloudscale.jwt.secret=integration-test-secret-0123456789-abcdef",
            "--cloudscale.ratelimit.trust-forward-headers=true",
            "--cloudscale.ratelimit.auth.capacity=" + capacity,
            "--cloudscale.ratelimit.auth.refill-per-second=0.001",
            "--cloudscale.jobs.worker-enabled=false");
    }

    private static HttpResponse<String> login(HttpClient http, int port, String ip) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json").header("X-Forwarded-For", ip)
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void limitIsSharedAcrossTwoInstances() throws Exception {
        int capacity = 6;
        try (ConfigurableApplicationContext a = start(capacity); ConfigurableApplicationContext b = start(capacity)) {
            int portA = Integer.parseInt(a.getBean(Environment.class).getProperty("local.server.port"));
            int portB = Integer.parseInt(b.getBean(Environment.class).getProperty("local.server.port"));
            HttpClient http = HttpClient.newHttpClient();
            String clientIp = "203.0.113." + (int) (Math.random() * 200 + 1) + "-" + UUID.randomUUID().toString().substring(0, 4);   // unique bucket per run

            int allowed = 0;
            int limited = 0;
            String retryAfter = null;
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> r = login(http, i % 2 == 0 ? portA : portB, clientIp);
                if (r.statusCode() == 401) {
                    allowed++;   // reached the handler (wrong password), i.e. not rate limited
                } else if (r.statusCode() == 429) {
                    limited++;
                    retryAfter = r.headers().firstValue("Retry-After").orElse(null);
                } else {
                    throw new AssertionError("unexpected status " + r.statusCode() + " " + r.body());
                }
            }
            assertEquals(capacity, allowed, "two instances together must allow exactly the bucket capacity");
            assertEquals(20 - capacity, limited);
            assertNotNull(retryAfter, "429 responses carry Retry-After");
            assertTrue(Integer.parseInt(retryAfter) >= 1);

            // another client is unaffected
            assertEquals(401, login(http, portA, "198.51.100." + UUID.randomUUID().toString().substring(0, 6)).statusCode());
        }
    }
}
