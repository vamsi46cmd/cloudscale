package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import io.cloudscale.Paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{id}/jobs")
@Tag(name = "Jobs")
public class JobController {
    public record CreateJob(@NotBlank String type, UUID projectId, JsonNode payload) {}

    public record JobView(UUID id, UUID workspaceId, UUID projectId, String type, String status, JsonNode result, String error,
                          int attemptCount, int maxAttempts, boolean cancelRequested, Instant createdAt, Instant startedAt,
                          Instant completedAt, Long durationMs) {
        static JobView of(Job j, boolean withResult) {
            return new JobView(j.id(), j.workspaceId(), j.projectId(), j.type(), j.status(), withResult ? j.result() : null, j.error(),
                    j.attemptCount(), j.maxAttempts(), j.cancelRequested(), j.createdAt(), j.startedAt(), j.completedAt(), j.durationMs());
        }
    }

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    private static UUID uid(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @PostMapping
    @Operation(summary = "Submit a job", description = "Types: CSV_ANALYTICS (payload.csv, payload.columns) and PROJECT_EXPORT (projectId). "
            + "Send an Idempotency-Key header to make retries safe: 202 for a new job, 200 with the original job for a replay.")
    public ResponseEntity<JobView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @Valid @RequestBody CreateJob req) {
        var s = jobs.submit(id, uid(jwt), req.type(), req.projectId(), req.payload(), idempotencyKey);
        return ResponseEntity.status(s.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(JobView.of(s.job(), false));
    }

    @GetMapping
    public PageResponse<JobView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        List<Job> items = jobs.list(id, uid(jwt), status, type, page, size);
        long total = jobs.count(id, status, type);
        return new PageResponse<>(items.stream().map(j -> JobView.of(j, false)).toList(), page, size, total, (int) ((total + size - 1) / size));
    }

    @GetMapping("/{jobId}")
    public JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID jobId) {
        return JobView.of(jobs.get(id, uid(jwt), jobId), true);
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(summary = "Cancel a job", description = "QUEUED jobs are cancelled immediately; RUNNING jobs stop at their next checkpoint. 409 if already finished.")
    public JobView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID jobId) {
        return JobView.of(jobs.cancel(id, uid(jwt), jobId), true);
    }

    @GetMapping("/{jobId}/download")
    @Operation(summary = "Download a finished PROJECT_EXPORT as a JSON file (workspace members only).")
    public ResponseEntity<JsonNode> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID jobId) {
        Job j = jobs.get(id, uid(jwt), jobId);
        if (!"PROJECT_EXPORT".equals(j.type()) || !"SUCCEEDED".equals(j.status())) {
            throw ApiException.conflict("Only a SUCCEEDED PROJECT_EXPORT job can be downloaded");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("project-export-" + j.id() + ".json").build().toString())
                .contentType(MediaType.APPLICATION_JSON).body(j.result());
    }
}
