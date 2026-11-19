package io.cloudscale;

import io.cloudscale.Entities.Role;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single authorization gate for tenant data. Called by every service method before any tenant-owned data is read
 * or written. Non-members get 404 (not 403) so resource ids cannot be probed across tenants.
 * Role checks always hit PostgreSQL: authorization decisions are never cached.
 */
@Component
public class Access {
    private final Repos.MembershipRepo members;
    private final JdbcTemplate jdbc;

    public Access(Repos.MembershipRepo members, JdbcTemplate jdbc) {
        this.members = members;
        this.jdbc = jdbc;
    }

    public Role role(UUID workspaceId, UUID userId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, userId).map(m -> m.role)
                .orElseThrow(() -> ApiException.notFound("Workspace not found"));
    }

    public Role require(UUID workspaceId, UUID userId, Role min) {
        Role r = role(workspaceId, userId);
        if (!r.atLeast(min)) {
            throw ApiException.forbidden("Requires role " + min + " or higher");
        }
        return r;
    }

    /** Audit trail written in the caller's transaction, so an action and its audit record commit or roll back together. */
    public void audit(UUID workspaceId, UUID actor, String action, String entityType, UUID entityId) {
        jdbc.update("INSERT INTO audit_events (id, workspace_id, actor_id, action, entity_type, entity_id) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), workspaceId, actor, action, entityType, entityId);
    }
}
