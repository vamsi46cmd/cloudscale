package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record Job(UUID id, UUID workspaceId, UUID projectId, String type, String status, JsonNode payload, JsonNode result,
                  String error, int attemptCount, int maxAttempts, boolean cancelRequested, Instant createdAt,
                  Instant startedAt, Instant completedAt, Instant leaseExpiresAt, Long durationMs, String traceparent) {}
