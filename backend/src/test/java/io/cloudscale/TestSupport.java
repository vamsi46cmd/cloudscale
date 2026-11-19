package io.cloudscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared helpers for integration tests. Real PostgreSQL and Redis (docker compose); no mocks of either. */
abstract class TestSupport {
    static final String DB_URL = System.getProperty("it.db.url", "jdbc:postgresql://localhost:5432/cloudscale_test");
    static final String[] PROPS = {
        "spring.datasource.url=" + DB_URL,
        "cloudscale.jwt.secret=integration-test-secret-0123456789-abcdef",
        "cloudscale.ratelimit.auth.capacity=100000",
        "cloudscale.ratelimit.api.capacity=100000",
        "cloudscale.ratelimit.trust-forward-headers=true",
        "management.server.port=-1",
        "management.tracing.enabled=false",
    };

    static {
        for (String p : PROPS) {
            String[] kv = p.split("=", 2);
            System.setProperty(kv[0], kv[1]);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;

    record Auth(String access, String refresh, UUID id, String email) {}

    protected MvcResult raw(HttpMethod method, String path, Auth who, Object body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = request(method, path);
        if (who != null) {
            b.header("Authorization", "Bearer " + who.access());
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : om.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn();
    }

    /** Sends a request, asserts the status, returns the JSON body (or a missing node for empty bodies). */
    protected JsonNode call(HttpMethod method, String path, Auth who, Object body, int expected, String... headers) throws Exception {
        MvcResult r = raw(method, path, who, body, headers);
        String content = r.getResponse().getContentAsString();
        assertEquals(expected, r.getResponse().getStatus(), method + " " + path + " -> " + content);
        return content.isBlank() ? om.missingNode() : om.readTree(content);
    }

    protected Auth register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        JsonNode t = call(HttpMethod.POST, "/api/v1/auth/register", null, java.util.Map.of("email", email, "password", "correct-horse-battery"), 201);
        Auth tmp = new Auth(t.get("accessToken").asText(), t.get("refreshToken").asText(), null, email);
        JsonNode me = call(HttpMethod.GET, "/api/v1/users/me", tmp, null, 200);
        return new Auth(tmp.access(), tmp.refresh(), UUID.fromString(me.get("id").asText()), email);
    }

    protected UUID createWorkspace(Auth a, String name) throws Exception {
        return UUID.fromString(call(HttpMethod.POST, "/api/v1/workspaces", a, java.util.Map.of("name", name), 201).get("id").asText());
    }

    protected UUID createProject(Auth a, UUID ws, String name) throws Exception {
        return UUID.fromString(call(HttpMethod.POST, "/api/v1/workspaces/" + ws + "/projects", a, java.util.Map.of("name", name), 201).get("id").asText());
    }

    /** Bounded polling instead of sleeps: returns as soon as the condition holds, fails after the timeout. */
    protected static <T> T await(String what, Duration timeout, Supplier<T> probe, java.util.function.Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = probe.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + what + "; last value: " + last);
        return last;
    }

    protected JsonNode awaitJob(Auth a, UUID ws, String jobId, String status, Duration timeout) throws Exception {
        return await("job " + jobId + " to reach " + status, timeout, () -> {
            try {
                return call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/jobs/" + jobId, a, null, 200);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, j -> status.equals(j.get("status").asText()));
    }
}
