package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed durable queue.
 *
 * Claiming uses UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED): concurrent workers lock disjoint rows, so a
 * job is never handed to two workers at the same time. A claim sets a lease; if the worker dies the lease expires and
 * another worker reclaims the job (so execution is AT-LEAST-ONCE, not exactly-once). attempt_count doubles as a fencing
 * token: every state change from a worker is conditional on "status = RUNNING AND attempt_count = my attempt", so a
 * slow/zombie worker that lost its lease cannot overwrite the result of the worker that took over.
 */
@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private JsonNode json(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        try {
            return s == null ? null : mapper.readTree(s);
        } catch (Exception e) {
            throw new SQLException("bad json in " + col, e);
        }
    }

    private final RowMapper<Job> rowMapper = (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getString("type"), rs.getString("status"), json(rs, "payload"), json(rs, "result"),
            rs.getString("error"), rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getBoolean("cancel_requested"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "completed_at"), ts(rs, "lease_expires_at"),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"), rs.getString("traceparent"));

    private String str(JsonNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** @return true if inserted, false if an identical (workspace, idempotency key) job already exists. */
    public boolean insert(UUID id, UUID ws, UUID projectId, String type, JsonNode payload, int maxAttempts, String idempotencyKey, UUID createdBy, String traceparent) {
        return jdbc.update("INSERT INTO processing_jobs (id, workspace_id, project_id, type, status, payload, max_attempts, idempotency_key, created_by, traceparent) "
                + "VALUES (?,?,?,?, 'QUEUED', ?::jsonb, ?, ?, ?, ?) "
                + "ON CONFLICT (workspace_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING",
                id, ws, projectId, type, str(payload), maxAttempts, idempotencyKey, createdBy, traceparent) == 1;
    }

    public Optional<Job> find(UUID ws, UUID id) {
        return jdbc.query("SELECT * FROM processing_jobs WHERE id = ? AND workspace_id = ?", rowMapper, id, ws).stream().findFirst();
    }

    public Optional<Job> findByIdempotencyKey(UUID ws, String key) {
        return jdbc.query("SELECT * FROM processing_jobs WHERE workspace_id = ? AND idempotency_key = ?", rowMapper, ws, key).stream().findFirst();
    }

    public List<Job> list(UUID ws, String status, String type, int limit, int offset) {
        List<Object> args = new ArrayList<>(List.of(ws));
        StringBuilder sql = new StringBuilder("SELECT id, workspace_id, project_id, type, status, NULL::jsonb AS payload, NULL::jsonb AS result, error, "
                + "attempt_count, max_attempts, cancel_requested, created_at, started_at, completed_at, lease_expires_at, duration_ms, traceparent "
                + "FROM processing_jobs WHERE workspace_id = ?");
        if (status != null) { sql.append(" AND status = ?"); args.add(status); }
        if (type != null) { sql.append(" AND type = ?"); args.add(type); }
        sql.append(" ORDER BY created_at DESC, id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    public long count(UUID ws, String status, String type) {
        List<Object> args = new ArrayList<>(List.of(ws));
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM processing_jobs WHERE workspace_id = ?");
        if (status != null) { sql.append(" AND status = ?"); args.add(status); }
        if (type != null) { sql.append(" AND type = ?"); args.add(type); }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** Atomically claims up to {@code limit} runnable jobs (queued and due, or running with an expired lease). */
    public List<Job> claim(int limit, double leaseSeconds) {
        return jdbc.query("UPDATE processing_jobs j SET status = 'RUNNING', started_at = now(), attempt_count = j.attempt_count + 1, "
                + "lease_expires_at = now() + make_interval(secs => ?::double precision), error = NULL "
                + "WHERE j.id IN (SELECT id FROM processing_jobs WHERE cancel_requested = false AND ("
                + "(status = 'QUEUED' AND run_at <= now()) OR (status = 'RUNNING' AND lease_expires_at < now() AND attempt_count < max_attempts)) "
                + "ORDER BY run_at, created_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING j.*", rowMapper, leaseSeconds, limit);
    }

    public boolean complete(UUID id, int attempt, JsonNode result, long durationMs) {
        return jdbc.update("UPDATE processing_jobs SET status = 'SUCCEEDED', result = ?::jsonb, completed_at = now(), duration_ms = ?, lease_expires_at = NULL "
                + "WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?", str(result), durationMs, id, attempt) == 1;
    }

    public boolean fail(UUID id, int attempt, String error, long durationMs) {
        return jdbc.update("UPDATE processing_jobs SET status = 'FAILED', error = ?, completed_at = now(), duration_ms = ?, lease_expires_at = NULL "
                + "WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?", abbreviate(error), durationMs, id, attempt) == 1;
    }

    /** Returns the job to the queue after a transient failure (or cancels it if cancellation was requested meanwhile). */
    public boolean retry(UUID id, int attempt, String error, double delaySeconds) {
        return jdbc.update("UPDATE processing_jobs SET status = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'QUEUED' END, "
                + "completed_at = CASE WHEN cancel_requested THEN now() ELSE NULL END, error = ?, "
                + "run_at = now() + make_interval(secs => ?::double precision), lease_expires_at = NULL "
                + "WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?", abbreviate(error), delaySeconds, id, attempt) == 1;
    }

    public boolean markCancelled(UUID id, int attempt) {
        return jdbc.update("UPDATE processing_jobs SET status = 'CANCELLED', completed_at = now(), lease_expires_at = NULL "
                + "WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?", id, attempt) == 1;
    }

    public boolean renewLease(UUID id, int attempt, double leaseSeconds) {
        return jdbc.update("UPDATE processing_jobs SET lease_expires_at = now() + make_interval(secs => ?::double precision) "
                + "WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?", leaseSeconds, id, attempt) == 1;
    }

    public boolean isCancelRequested(UUID id) {
        Boolean b = jdbc.queryForObject("SELECT cancel_requested FROM processing_jobs WHERE id = ?", Boolean.class, id);
        return b != null && b;
    }

    /** Cancels a QUEUED job immediately; flags a RUNNING job so its worker stops at the next checkpoint. */
    public boolean requestCancel(UUID ws, UUID id) {
        return jdbc.update("UPDATE processing_jobs SET status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END, "
                + "completed_at = CASE WHEN status = 'QUEUED' THEN now() ELSE completed_at END, cancel_requested = true "
                + "WHERE id = ? AND workspace_id = ? AND status IN ('QUEUED', 'RUNNING')", id, ws) == 1;
    }

    /** Housekeeping: finish jobs that can never be picked up again. */
    public void sweep() {
        jdbc.update("UPDATE processing_jobs SET status = 'FAILED', error = 'Lease expired and max attempts exhausted', completed_at = now(), lease_expires_at = NULL "
                + "WHERE status = 'RUNNING' AND lease_expires_at < now() AND attempt_count >= max_attempts AND cancel_requested = false");
        jdbc.update("UPDATE processing_jobs SET status = 'CANCELLED', completed_at = now(), lease_expires_at = NULL "
                + "WHERE (status = 'RUNNING' AND cancel_requested AND lease_expires_at < now()) OR (status = 'QUEUED' AND cancel_requested)");
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
