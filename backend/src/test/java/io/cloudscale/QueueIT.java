package io.cloudscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** Queue-level concurrency and recovery tests. The worker is OFF here so the test controls every claim. */
@SpringBootTest(properties = {"cloudscale.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class QueueIT extends TestSupport {
    static { System.setProperty("spring.datasource.url", DB_URL); }

    @Autowired JobRepository repo;
    private UUID user;
    private UUID ws;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM processing_jobs");   // isolated test database: start from an empty queue
        user = UUID.randomUUID();
        ws = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')", user, "q-" + user + "@example.com");
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, 'q', ?)", ws, user);
    }

    private JsonNode payload() {
        return om.createObjectNode().put("csv", "a\n1\n");
    }

    private UUID enqueue() {
        UUID id = UUID.randomUUID();
        assertTrue(repo.insert(id, ws, null, "CSV_ANALYTICS", payload(), 3, null, user, null));
        return id;
    }

    @Test
    void manyWorkersNeverClaimTheSameJob() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            ids.add(enqueue());
        }
        ConcurrentLinkedQueue<UUID> claimed = new ConcurrentLinkedQueue<>();
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            fs.add(pool.submit(() -> {
                start.await();
                while (true) {
                    List<Job> batch = repo.claim(3, 30);
                    if (batch.isEmpty()) {
                        return null;
                    }
                    batch.forEach(j -> claimed.add(j.id()));
                }
            }));
        }
        start.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        Set<UUID> unique = Set.copyOf(claimed);
        assertEquals(80, claimed.size(), "every job claimed exactly once in total");
        assertEquals(80, unique.size(), "no job handed to two workers");
        assertTrue(unique.containsAll(ids));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processing_jobs WHERE attempt_count <> 1 OR status <> 'RUNNING'", Integer.class));
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleWorkerCannotOverwrite() {
        UUID id = enqueue();
        Job first = repo.claim(1, 30).get(0);
        assertEquals(1, first.attemptCount());
        assertTrue(repo.claim(1, 30).isEmpty(), "a live lease is not stealable");

        // simulate a crashed worker: its lease runs out
        jdbc.update("UPDATE processing_jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);
        Job second = repo.claim(1, 30).get(0);
        assertEquals(id, second.id());
        assertEquals(2, second.attemptCount(), "reclaimed: attempt count is the fencing token");

        // the original (zombie) worker finally finishes: its write is rejected, the new owner's is accepted
        assertFalse(repo.complete(id, first.attemptCount(), om.createObjectNode().put("by", "zombie"), 1));
        assertTrue(repo.complete(id, second.attemptCount(), om.createObjectNode().put("by", "owner"), 1));
        assertEquals("owner", jdbc.queryForObject("SELECT result->>'by' FROM processing_jobs WHERE id = ?", String.class, id));
    }

    @Test
    void jobsThatExhaustAttemptsAfterCrashesAreFailedNotLooped() {
        UUID id = UUID.randomUUID();
        repo.insert(id, ws, null, "CSV_ANALYTICS", payload(), 2, null, user, null);
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(1, repo.claim(1, 30).size());
            jdbc.update("UPDATE processing_jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", id);   // worker dies
        }
        assertTrue(repo.claim(1, 30).isEmpty(), "no third attempt");
        repo.sweep();
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id = ?", String.class, id));
    }

    @Test
    void retryBackoffDelaysRunAtAndCancelWinsOverRetry() {
        UUID id = enqueue();
        Job j = repo.claim(1, 30).get(0);
        assertTrue(repo.retry(id, j.attemptCount(), "boom", 60));
        assertTrue(repo.claim(1, 30).isEmpty(), "not runnable until run_at (backoff)");
        jdbc.update("UPDATE processing_jobs SET run_at = now() WHERE id = ?", id);
        Job again = repo.claim(1, 30).get(0);
        assertEquals(2, again.attemptCount());

        assertTrue(repo.requestCancel(ws, id));          // running: flagged
        assertTrue(repo.retry(id, again.attemptCount(), "boom", 0));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id = ?", String.class, id));
    }

    @Test
    void queuedJobsCancelImmediatelyAndOnlyWithinTheirWorkspace() {
        UUID id = enqueue();
        assertFalse(repo.requestCancel(UUID.randomUUID(), id), "another workspace cannot cancel it");
        assertTrue(repo.requestCancel(ws, id));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM processing_jobs WHERE id = ?", String.class, id));
        assertTrue(repo.claim(1, 30).isEmpty());
        assertFalse(repo.requestCancel(ws, id), "finished jobs cannot be cancelled again");
    }
}
