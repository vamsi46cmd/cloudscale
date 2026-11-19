package io.cloudscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;

/** End-to-end API tests against real PostgreSQL + Redis, with an in-process worker. Needs: docker compose up -d postgres redis. */
@SpringBootTest(properties = {
    "cloudscale.jobs.worker-enabled=true", "cloudscale.jobs.test-hooks=true", "cloudscale.jobs.poll-interval=100ms",
    "cloudscale.jobs.lease=3s", "cloudscale.jobs.backoff-base=1s", "cloudscale.jobs.concurrency=4"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ApiIT extends TestSupport {
    static { System.setProperty("spring.datasource.url", DB_URL); }

    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;

    private static final String CSV = "item,price,qty\na,0.10,1\nb,0.20,2\nc,10.50,3\n";

    private String jobsPath(UUID ws) { return "/api/v1/workspaces/" + ws + "/jobs"; }

    private JsonNode submitCsv(Auth a, UUID ws, String csv, List<String> cols, Map<String, Object> simulate, String key) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>(Map.of("csv", csv, "columns", cols));
        if (simulate != null) {
            payload.put("simulate", simulate);
        }
        return call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "CSV_ANALYTICS", "payload", payload), 202,
                key == null ? new String[0] : new String[] {"Idempotency-Key", key});
    }

    @Test
    void registerLoginWorkspaceProjectJobResultAuditAndUnauthorized() throws Exception {
        Auth alice = register();
        UUID ws = createWorkspace(alice, "Acme");
        UUID project = createProject(alice, ws, "Launch");

        JsonNode job = submitCsv(alice, ws, CSV, List.of("price", "qty"), null, null);
        JsonNode done = awaitJob(alice, ws, job.get("id").asText(), "SUCCEEDED", Duration.ofSeconds(15));
        JsonNode price = done.get("result").get("columns").get("price");
        assertEquals(3, price.get("count").asInt());
        assertEquals(0, price.get("sum").decimalValue().compareTo(new java.math.BigDecimal("10.80")));
        assertEquals(1, done.get("attemptCount").asInt());
        assertNotNull(done.get("durationMs"));

        JsonNode export = call(HttpMethod.POST, jobsPath(ws), alice, Map.of("type", "PROJECT_EXPORT", "projectId", project.toString()), 202);
        awaitJob(alice, ws, export.get("id").asText(), "SUCCEEDED", Duration.ofSeconds(15));
        JsonNode file = call(HttpMethod.GET, jobsPath(ws) + "/" + export.get("id").asText() + "/download", alice, null, 200);
        assertEquals("Launch", file.get("project").get("name").asText());

        // audit trail written for each action
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_events WHERE workspace_id = ? ORDER BY created_at", String.class, ws);
        assertTrue(actions.containsAll(List.of("WORKSPACE_CREATED", "PROJECT_CREATED", "JOB_SUBMITTED")), actions.toString());

        // metrics are exported by the application
        double succeeded = await("job metrics", Duration.ofSeconds(5),
            () -> meters.find("cloudscale.jobs.finished").tag("outcome", "succeeded").counters().stream().mapToDouble(c -> c.count()).sum(),
            v -> v >= 2);
        assertTrue(succeeded >= 2);

        // unauthenticated and bad tokens
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, null, null, 401);
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, new Auth("not.a.jwt", null, null, null), null, 401);
    }

    @Test
    void crossTenantAccessIsBlockedEverywhere() throws Exception {
        Auth alice = register();
        Auth mallory = register();
        UUID ws = createWorkspace(alice, "Alice WS");
        UUID project = createProject(alice, ws, "Secret project");
        JsonNode job = submitCsv(alice, ws, CSV, List.of("price"), null, null);
        String jobId = job.get("id").asText();
        String base = "/api/v1/workspaces/" + ws;

        // guessing real ids from another tenant yields 404 (no existence leak), never data
        call(HttpMethod.GET, base, mallory, null, 404);
        call(HttpMethod.PATCH, base, mallory, Map.of("name", "pwned"), 404);
        call(HttpMethod.DELETE, base, mallory, null, 404);
        call(HttpMethod.GET, base + "/members", mallory, null, 404);
        call(HttpMethod.POST, base + "/members", mallory, Map.of("email", mallory.email(), "role", "ADMIN"), 404);
        call(HttpMethod.GET, base + "/projects", mallory, null, 404);
        call(HttpMethod.GET, base + "/projects/" + project, mallory, null, 404);
        call(HttpMethod.PATCH, base + "/projects/" + project, mallory, Map.of("name", "pwned"), 404);
        call(HttpMethod.DELETE, base + "/projects/" + project, mallory, null, 404);
        call(HttpMethod.GET, base + "/jobs", mallory, null, 404);
        call(HttpMethod.GET, base + "/jobs/" + jobId, mallory, null, 404);
        call(HttpMethod.POST, base + "/jobs/" + jobId + "/cancel", mallory, null, 404);
        call(HttpMethod.POST, base + "/jobs", mallory, Map.of("type", "PROJECT_EXPORT", "projectId", project.toString()), 404);

        // Mallory's own workspace + Alice's project id: still not found (project lookups are workspace-scoped)
        UUID malloryWs = createWorkspace(mallory, "Mallory WS");
        call(HttpMethod.GET, "/api/v1/workspaces/" + malloryWs + "/projects/" + project, mallory, null, 404);
        call(HttpMethod.POST, "/api/v1/workspaces/" + malloryWs + "/jobs", mallory, Map.of("type", "PROJECT_EXPORT", "projectId", project.toString()), 404);
        assertEquals("Alice WS", call(HttpMethod.GET, base, alice, null, 200).get("name").asText());
        // Mallory only sees her own workspace in the list
        JsonNode list = call(HttpMethod.GET, "/api/v1/workspaces", mallory, null, 200);
        assertEquals(1, list.get("items").size());
    }

    @Test
    void rolesAndPrivilegeEscalationRules() throws Exception {
        Auth owner = register();
        Auth member = register();
        Auth admin = register();
        Auth extra = register();
        UUID ws = createWorkspace(owner, "Roles");
        String base = "/api/v1/workspaces/" + ws;
        UUID project = createProject(owner, ws, "p");

        call(HttpMethod.POST, base + "/members", owner, Map.of("email", member.email(), "role", "MEMBER"), 201);
        call(HttpMethod.POST, base + "/members", owner, Map.of("email", admin.email(), "role", "ADMIN"), 201);

        // MEMBER: can work with projects, cannot administer
        call(HttpMethod.POST, base + "/projects", member, Map.of("name", "by member"), 201);
        call(HttpMethod.DELETE, base + "/projects/" + project, member, null, 403);
        call(HttpMethod.POST, base + "/members", member, Map.of("email", extra.email(), "role", "MEMBER"), 403);
        call(HttpMethod.PATCH, base, member, Map.of("name", "x"), 403);
        call(HttpMethod.PATCH, base + "/members/" + member.id(), member, Map.of("role", "ADMIN"), 403);   // self-promotion

        // ADMIN: can add MEMBERs, cannot mint admins, change roles, touch the owner, or delete the workspace
        call(HttpMethod.POST, base + "/members", admin, Map.of("email", extra.email(), "role", "ADMIN"), 403);
        call(HttpMethod.PATCH, base + "/members/" + member.id(), admin, Map.of("role", "ADMIN"), 403);
        call(HttpMethod.DELETE, base + "/members/" + owner.id(), admin, null, 409);
        call(HttpMethod.DELETE, base, admin, null, 403);
        call(HttpMethod.PATCH, base, admin, Map.of("name", "renamed by admin"), 200);
        call(HttpMethod.POST, base + "/members", admin, Map.of("email", extra.email(), "role", "MEMBER"), 201);
        call(HttpMethod.DELETE, base + "/projects/" + project, admin, null, 204);

        // nobody can grant OWNER or demote the owner
        call(HttpMethod.POST, base + "/members", owner, Map.of("email", extra.email(), "role", "OWNER"), 403);
        call(HttpMethod.PATCH, base + "/members/" + member.id(), owner, Map.of("role", "OWNER"), 403);
        call(HttpMethod.PATCH, base + "/members/" + owner.id(), owner, Map.of("role", "MEMBER"), 403);

        // owner can change roles; a removed member loses access immediately (authorization is never cached)
        call(HttpMethod.PATCH, base + "/members/" + member.id(), owner, Map.of("role", "ADMIN"), 200);
        call(HttpMethod.DELETE, base + "/members/" + member.id(), owner, null, 204);
        call(HttpMethod.GET, base, member, null, 404);
        call(HttpMethod.DELETE, base, owner, null, 204);
        call(HttpMethod.GET, base, owner, null, 404);
    }

    @Test
    void refreshTokenRotationReuseDetectionAndLogout() throws Exception {
        Auth u = register();
        JsonNode r1 = call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", u.refresh()), 200);
        String second = r1.get("refreshToken").asText();
        assertFalse(second.equals(u.refresh()));
        call(HttpMethod.GET, "/api/v1/users/me", new Auth(r1.get("accessToken").asText(), second, u.id(), u.email()), null, 200);

        // replaying the already-used token is rejected AND burns the whole family (the newer token stops working too)
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", u.refresh()), 401);
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", second), 401);

        // login + logout revokes server-side
        JsonNode login = call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("email", u.email(), "password", "correct-horse-battery"), 200);
        call(HttpMethod.POST, "/api/v1/auth/logout", null, Map.of("refreshToken", login.get("refreshToken").asText()), 204);
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", login.get("refreshToken").asText()), 401);

        call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("email", u.email(), "password", "wrong-password-123"), 401);
        call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("email", "nobody@example.com", "password", "wrong-password-123"), 401);
        call(HttpMethod.POST, "/api/v1/auth/register", null, Map.of("email", u.email(), "password", "correct-horse-battery"), 409);
        call(HttpMethod.POST, "/api/v1/auth/register", null, Map.of("email", "short@example.com", "password", "short"), 400);
        call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", "garbage"), 401);
    }

    @Test
    void idempotencyKeyCollapsesDuplicatesIncludingConcurrentOnes() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Idem");
        String key = "key-" + UUID.randomUUID();

        JsonNode first = submitCsv(a, ws, CSV, List.of("price"), null, key);
        JsonNode second = call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "CSV_ANALYTICS", "payload", Map.of("csv", CSV, "columns", List.of("price"))), 200,
                "Idempotency-Key", key);
        assertEquals(first.get("id").asText(), second.get("id").asText());
        // same key, different request: refused
        call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "CSV_ANALYTICS", "payload", Map.of("csv", CSV, "columns", List.of("qty"))), 409, "Idempotency-Key", key);

        // 8 concurrent submissions with one key -> exactly one job row
        String key2 = "key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Callable<Integer> c = () -> raw(HttpMethod.POST, jobsPath(ws), a,
                    Map.of("type", "CSV_ANALYTICS", "payload", Map.of("csv", CSV, "columns", List.of("price"))), "Idempotency-Key", key2).getResponse().getStatus();
            results.add(pool.submit(c));
        }
        int created = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            assertTrue(s == 202 || s == 200, "unexpected status " + s);
            if (s == 202) created++;
        }
        pool.shutdown();
        assertEquals(1, created, "exactly one request may create the job");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processing_jobs WHERE workspace_id = ? AND idempotency_key = ?", Integer.class, ws, key2));
    }

    @Test
    void workerRetriesTransientFailuresThenSucceeds() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Retry");
        JsonNode job = submitCsv(a, ws, CSV, List.of("price"), Map.of("failAttempts", 2), null);
        JsonNode done = awaitJob(a, ws, job.get("id").asText(), "SUCCEEDED", Duration.ofSeconds(25));
        assertEquals(3, done.get("attemptCount").asInt(), "two failures then success on the third attempt");
    }

    @Test
    void workerGivesUpAfterMaxAttemptsAndFailsMalformedJobsImmediately() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Failures");
        JsonNode doomed = submitCsv(a, ws, CSV, List.of("price"), Map.of("failAttempts", 99), null);
        JsonNode failed = awaitJob(a, ws, doomed.get("id").asText(), "FAILED", Duration.ofSeconds(25));
        assertEquals(3, failed.get("attemptCount").asInt());
        assertTrue(failed.get("error").asText().contains("Gave up"));

        // malformed content (unknown column): permanent failure, no retries
        JsonNode bad = submitCsv(a, ws, CSV, List.of("does_not_exist"), null, null);
        JsonNode badDone = awaitJob(a, ws, bad.get("id").asText(), "FAILED", Duration.ofSeconds(15));
        assertEquals(1, badDone.get("attemptCount").asInt());
        JsonNode nonNumeric = submitCsv(a, ws, "item,price\na,abc\n", List.of("price"), null, null);
        assertTrue(awaitJob(a, ws, nonNumeric.get("id").asText(), "FAILED", Duration.ofSeconds(15)).get("error").asText().contains("not numeric"));

        // malformed / oversized submissions are rejected up front
        call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "CSV_ANALYTICS", "payload", Map.of("columns", List.of("x"))), 400);
        call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "NOPE"), 400);
        call(HttpMethod.POST, jobsPath(ws), a, Map.of("type", "CSV_ANALYTICS", "payload", Map.of("csv", "x".repeat(1_000_001), "columns", List.of("x"))), 400);
    }

    @Test
    void longRunningJobCanBeCancelled() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Cancel");
        JsonNode job = submitCsv(a, ws, CSV, List.of("price"), Map.of("sleepMs", 60000), null);
        String id = job.get("id").asText();
        awaitJob(a, ws, id, "RUNNING", Duration.ofSeconds(15));
        JsonNode cancelled = call(HttpMethod.POST, jobsPath(ws) + "/" + id + "/cancel", a, null, 200);
        assertTrue(cancelled.get("cancelRequested").asBoolean());
        awaitJob(a, ws, id, "CANCELLED", Duration.ofSeconds(15));
        call(HttpMethod.POST, jobsPath(ws) + "/" + id + "/cancel", a, null, 409);   // already finished
    }

    @Test
    void concurrentWorkspaceUpdatesAllowExactlyOneWinner() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Original");
        long version = call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200).get("version").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "writer-" + i;
            results.add(pool.submit(() -> raw(HttpMethod.PATCH, "/api/v1/workspaces/" + ws, a, Map.of("name", name, "version", version)).getResponse().getStatus()));
        }
        int ok = 0;
        int conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 200) ok++;
            else if (s == 409) conflicts++;
            else throw new AssertionError("unexpected status " + s);
        }
        pool.shutdown();
        assertEquals(1, ok, "optimistic locking must let exactly one concurrent writer win");
        assertEquals(7, conflicts);
    }

    @Test
    void concurrentDuplicateMemberAddsConflict() throws Exception {
        Auth owner = register();
        Auth guest = register();
        UUID ws = createWorkspace(owner, "Members");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> raw(HttpMethod.POST, "/api/v1/workspaces/" + ws + "/members", owner,
                    Map.of("email", guest.email(), "role", "MEMBER")).getResponse().getStatus()));
        }
        int created = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) created++;
            else assertEquals(409, s);
        }
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM workspace_memberships WHERE workspace_id = ?", Integer.class, ws));
    }

    @Test
    void cacheServesReadsAndIsInvalidatedOnWrite() throws Exception {
        Auth a = register();
        Auth other = register();
        UUID ws = createWorkspace(a, "Before");
        UUID project = createProject(a, ws, "Cached project");
        String key = CacheService.workspaceKey(ws);
        redis.delete(key);

        double hits0 = meters.get("cloudscale.cache.requests").tag("result", "hit").counter().count();
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200);          // miss -> fills cache
        assertEquals(Boolean.TRUE, redis.hasKey(key));
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200);          // hit
        assertTrue(meters.get("cloudscale.cache.requests").tag("result", "hit").counter().count() > hits0);

        // project count in the cached summary is invalidated when a project is added
        assertEquals(1, call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200).get("projectCount").asInt());
        createProject(a, ws, "second");
        assertEquals(2, call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200).get("projectCount").asInt(), "stale cache must not survive a write");

        // update invalidates the cached name
        call(HttpMethod.PATCH, "/api/v1/workspaces/" + ws, a, Map.of("name", "After"), 200);
        assertEquals("After", call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200).get("name").asText());

        // cached project metadata is invalidated on update and delete
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects/" + project, a, null, 200);
        call(HttpMethod.PATCH, "/api/v1/workspaces/" + ws + "/projects/" + project, a, Map.of("name", "renamed"), 200);
        assertEquals("renamed", call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects/" + project, a, null, 200).get("name").asText());
        call(HttpMethod.DELETE, "/api/v1/workspaces/" + ws + "/projects/" + project, a, null, 204);
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects/" + project, a, null, 404);

        // a warm cache never lets another tenant in: authorization is checked before the cache is consulted
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, a, null, 200);
        assertEquals(Boolean.TRUE, redis.hasKey(key));
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws, other, null, 404);
        assertFalse(CacheService.workspaceKey(ws).equals(CacheService.workspaceKey(UUID.randomUUID())));
    }

    @Test
    void paginationFilteringAndSortingAreBounded() throws Exception {
        Auth a = register();
        UUID ws = createWorkspace(a, "Paging");
        for (String n : List.of("b", "a", "c")) {
            createProject(a, ws, n);
        }
        JsonNode page = call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects?size=2&sort=name,asc", a, null, 200);
        assertEquals(2, page.get("items").size());
        assertEquals("a", page.get("items").get(0).get("name").asText());
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects?size=1000", a, null, 400);
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects?sort=passwordHash", a, null, 400);
        call(HttpMethod.GET, "/api/v1/workspaces/" + ws + "/projects?status=ARCHIVED", a, null, 200);
        call(HttpMethod.GET, jobsPath(ws) + "?status=BOGUS", a, null, 400);
    }
}
