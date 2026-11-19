package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cloudscale.Entities.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
    public static final Set<String> TYPES = Set.of("CSV_ANALYTICS", "PROJECT_EXPORT");
    public static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");

    public record Submission(Job job, boolean created) {}

    private final JobRepository repo;
    private final Access access;
    private final Repos.ProjectRepo projects;
    private final ObjectMapper mapper;
    private final Props props;
    private final org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer;

    public JobService(JobRepository repo, Access access, Repos.ProjectRepo projects, ObjectMapper mapper, Props props,
                      org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer) {
        this.tracer = tracer;
        this.repo = repo;
        this.access = access;
        this.projects = projects;
        this.mapper = mapper;
        this.props = props;
    }

    @Transactional
    public Submission submit(UUID ws, UUID userId, String type, UUID projectId, JsonNode payload, String idempotencyKey) {
        access.require(ws, userId, Role.MEMBER);
        if (type == null || !TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of " + TYPES);
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw ApiException.badRequest("Idempotency-Key must be 1-100 characters");
        }
        ObjectNode clean = mapper.createObjectNode();   // store only fields we understand
        if ("CSV_ANALYTICS".equals(type)) {
            String csv = payload == null ? null : payload.path("csv").asText(null);
            if (csv == null || csv.isBlank()) {
                throw ApiException.badRequest("payload.csv is required");
            }
            if (csv.length() > props.jobs().maxCsvChars()) {
                throw ApiException.badRequest("payload.csv exceeds " + props.jobs().maxCsvChars() + " characters");
            }
            JsonNode cols = payload.path("columns");
            if (!cols.isArray() || cols.isEmpty() || cols.size() > JobProcessors.MAX_COLUMNS) {
                throw ApiException.badRequest("payload.columns must list 1-" + JobProcessors.MAX_COLUMNS + " column names");
            }
            for (JsonNode c : cols) {
                if (!c.isTextual() || c.asText().isBlank() || c.asText().length() > 100) {
                    throw ApiException.badRequest("payload.columns must be non-empty strings");
                }
            }
            clean.put("csv", csv);
            clean.set("columns", cols);
            projectId = null;
        } else {
            if (projectId == null) {
                throw ApiException.badRequest("projectId is required for PROJECT_EXPORT");
            }
            projects.findByIdAndWorkspaceId(projectId, ws).orElseThrow(() -> ApiException.notFound("Project not found"));
        }
        if (props.jobs().testHooks() && payload != null && payload.path("simulate").isObject()) {
            clean.set("simulate", payload.get("simulate"));
        }

        if (idempotencyKey != null) {
            var existing = repo.findByIdempotencyKey(ws, idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), type, projectId, clean);
            }
        }
        UUID id = UUID.randomUUID();
        boolean inserted = repo.insert(id, ws, projectId, type, clean, props.jobs().maxAttempts(), idempotencyKey, userId, currentTraceparent());
        if (!inserted) {   // lost a race with a concurrent request using the same key
            return replay(repo.findByIdempotencyKey(ws, idempotencyKey).orElseThrow(), type, projectId, clean);
        }
        access.audit(ws, userId, "JOB_SUBMITTED", "JOB", id);
        return new Submission(repo.find(ws, id).orElseThrow(), true);
    }

    /** W3C traceparent of the active span (the HTTP request), stored so the worker can continue the same trace. */
    private String currentTraceparent() {
        io.micrometer.tracing.Tracer t = tracer.getIfAvailable();
        if (t == null || t.currentSpan() == null) {
            return null;
        }
        io.micrometer.tracing.TraceContext c = t.currentSpan().context();
        if (c.traceId() == null || c.traceId().length() != 32 || c.spanId() == null) {
            return null;
        }
        return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
    }

    private Submission replay(Job existing, String type, UUID projectId, JsonNode payload) {
        boolean same = existing.type().equals(type) && java.util.Objects.equals(existing.projectId(), projectId)
                && existing.payload().equals(payload);
        if (!same) {
            throw ApiException.conflict("Idempotency-Key was already used with a different request");
        }
        return new Submission(existing, false);
    }

    @Transactional(readOnly = true)
    public Job get(UUID ws, UUID userId, UUID id) {
        access.role(ws, userId);
        return repo.find(ws, id).orElseThrow(() -> ApiException.notFound("Job not found"));
    }

    @Transactional(readOnly = true)
    public List<Job> list(UUID ws, UUID userId, String status, String type, int page, int size) {
        access.role(ws, userId);
        validateFilters(status, type, page, size);
        return repo.list(ws, status, type, size, page * size);
    }

    @Transactional(readOnly = true)
    public long count(UUID ws, String status, String type) {
        return repo.count(ws, status, type);
    }

    private static void validateFilters(String status, String type, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be >= 0 and size between 1 and 100");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of " + STATUSES);
        }
        if (type != null && !TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of " + TYPES);
        }
    }

    @Transactional
    public Job cancel(UUID ws, UUID userId, UUID id) {
        access.require(ws, userId, Role.MEMBER);
        repo.find(ws, id).orElseThrow(() -> ApiException.notFound("Job not found"));
        if (!repo.requestCancel(ws, id)) {
            throw ApiException.conflict("Job has already finished");
        }
        access.audit(ws, userId, "JOB_CANCEL_REQUESTED", "JOB", id);
        return repo.find(ws, id).orElseThrow();
    }
}
