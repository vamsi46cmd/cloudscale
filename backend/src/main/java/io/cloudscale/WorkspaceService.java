package io.cloudscale;

import io.cloudscale.Entities.AppUser;
import io.cloudscale.Entities.Membership;
import io.cloudscale.Entities.Role;
import io.cloudscale.Entities.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {
    /** Cached read model: contains no per-user data (the caller's role is added after the authorization check). */
    public record WorkspaceSummary(UUID id, String name, UUID ownerId, long memberCount, long projectCount,
                                   Instant createdAt, Instant updatedAt, Long version) {}

    public record WorkspaceView(UUID id, String name, UUID ownerId, long memberCount, long projectCount,
                                Instant createdAt, Instant updatedAt, Long version, Role role) {
        static WorkspaceView of(WorkspaceSummary s, Role role) {
            return new WorkspaceView(s.id(), s.name(), s.ownerId(), s.memberCount(), s.projectCount(), s.createdAt(), s.updatedAt(), s.version(), role);
        }
    }

    public record MemberView(UUID userId, String email, Role role) {}

    public static final Set<String> SORTABLE = Set.of("createdAt", "name", "updatedAt");

    private final Repos.WorkspaceRepo workspaces;
    private final Repos.MembershipRepo members;
    private final Repos.UserRepo users;
    private final Repos.ProjectRepo projects;
    private final Access access;
    private final CacheService cache;
    private final JdbcTemplate jdbc;

    public WorkspaceService(Repos.WorkspaceRepo workspaces, Repos.MembershipRepo members, Repos.UserRepo users,
                            Repos.ProjectRepo projects, Access access, CacheService cache, JdbcTemplate jdbc) {
        this.workspaces = workspaces;
        this.members = members;
        this.users = users;
        this.projects = projects;
        this.access = access;
        this.cache = cache;
        this.jdbc = jdbc;
    }

    @Transactional
    public WorkspaceView create(UUID userId, String name) {
        Workspace w = new Workspace();
        w.id = UUID.randomUUID();
        w.name = name.trim();
        w.ownerId = userId;
        workspaces.saveAndFlush(w);
        Membership m = new Membership();
        m.workspaceId = w.id;
        m.userId = userId;
        m.role = Role.OWNER;
        members.save(m);
        access.audit(w.id, userId, "WORKSPACE_CREATED", "WORKSPACE", w.id);
        return WorkspaceView.of(summary(w), Role.OWNER);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Workspace> list(UUID userId, Pageable p) {
        return workspaces.findForUser(userId, p);
    }

    @Transactional(readOnly = true)
    public WorkspaceView get(UUID wsId, UUID userId) {
        Role role = access.role(wsId, userId);   // authorization first, from PostgreSQL, never from cache
        WorkspaceSummary s = cache.get(CacheService.workspaceKey(wsId), WorkspaceSummary.class).orElseGet(() -> {
            Workspace w = workspaces.findById(wsId).orElseThrow(() -> ApiException.notFound("Workspace not found"));
            WorkspaceSummary fresh = summary(w);
            cache.put(CacheService.workspaceKey(wsId), fresh);
            return fresh;
        });
        return WorkspaceView.of(s, role);
    }

    @Transactional
    public WorkspaceView update(UUID wsId, UUID userId, String name, Long expectedVersion) {
        Role role = access.require(wsId, userId, Role.ADMIN);
        Workspace w = workspaces.findById(wsId).orElseThrow(() -> ApiException.notFound("Workspace not found"));
        if (expectedVersion != null && !expectedVersion.equals(w.version)) {
            throw ApiException.conflict("Workspace version is " + w.version + ", not " + expectedVersion);
        }
        w.name = name.trim();
        workspaces.saveAndFlush(w);   // @Version check happens here: a concurrent update fails with 409
        access.audit(wsId, userId, "WORKSPACE_UPDATED", "WORKSPACE", wsId);
        cache.evictAfterCommit(CacheService.workspaceKey(wsId));
        return WorkspaceView.of(summary(w), role);
    }

    @Transactional
    public void delete(UUID wsId, UUID userId) {
        access.require(wsId, userId, Role.OWNER);
        workspaces.deleteById(wsId);   // memberships, projects and jobs are removed by ON DELETE CASCADE
        workspaces.flush();
        access.audit(wsId, userId, "WORKSPACE_DELETED", "WORKSPACE", wsId);
        cache.evictWorkspaceAfterCommit(wsId);
    }

    // ---- members ----

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(UUID wsId, UUID userId) {
        access.role(wsId, userId);
        return jdbc.query("SELECT m.user_id, u.email, m.role FROM workspace_memberships m JOIN users u ON u.id = m.user_id "
                + "WHERE m.workspace_id = ? ORDER BY m.created_at, m.user_id LIMIT 500",
                (rs, i) -> new MemberView(rs.getObject("user_id", UUID.class), rs.getString("email"), Role.valueOf(rs.getString("role"))), wsId);
    }

    @Transactional
    public MemberView addMember(UUID wsId, UUID actor, String email, Role role) {
        Role actorRole = access.require(wsId, actor, Role.ADMIN);
        if (role == Role.OWNER) {
            throw ApiException.forbidden("The OWNER role cannot be granted");
        }
        if (role == Role.ADMIN && actorRole != Role.OWNER) {
            throw ApiException.forbidden("Only the OWNER can grant ADMIN");   // prevents ADMIN -> ADMIN escalation chains
        }
        AppUser target = users.findByEmailIgnoreCase(email.trim()).orElseThrow(() -> ApiException.notFound("No user with that email"));
        if (members.findByWorkspaceIdAndUserId(wsId, target.id).isPresent()) {
            throw ApiException.conflict("User is already a member");
        }
        Membership m = new Membership();
        m.workspaceId = wsId;
        m.userId = target.id;
        m.role = role;
        members.saveAndFlush(m);
        access.audit(wsId, actor, "MEMBER_ADDED", "USER", target.id);
        cache.evictAfterCommit(CacheService.workspaceKey(wsId));
        return new MemberView(target.id, target.email, role);
    }

    @Transactional
    public MemberView changeRole(UUID wsId, UUID actor, UUID target, Role newRole) {
        access.require(wsId, actor, Role.OWNER);   // only the OWNER changes roles
        if (newRole == Role.OWNER) {
            throw ApiException.forbidden("The OWNER role cannot be granted");
        }
        Membership m = members.findByWorkspaceIdAndUserId(wsId, target).orElseThrow(() -> ApiException.notFound("Member not found"));
        if (m.role == Role.OWNER) {
            throw ApiException.forbidden("The OWNER's role cannot be changed");
        }
        m.role = newRole;
        members.saveAndFlush(m);   // @Version: two conflicting concurrent changes -> one gets 409
        access.audit(wsId, actor, "MEMBER_ROLE_CHANGED", "USER", target);
        String email = users.findById(target).map(u -> u.email).orElse("");
        return new MemberView(target, email, newRole);
    }

    @Transactional
    public void removeMember(UUID wsId, UUID actor, UUID target) {
        Role actorRole = access.role(wsId, actor);
        Membership m = members.findByWorkspaceIdAndUserId(wsId, target).orElseThrow(() -> ApiException.notFound("Member not found"));
        if (m.role == Role.OWNER) {
            throw ApiException.conflict("The OWNER cannot be removed; delete the workspace instead");
        }
        boolean self = actor.equals(target);
        if (!self && !(actorRole.atLeast(Role.ADMIN) && actorRole.ordinal() > m.role.ordinal())) {
            throw ApiException.forbidden("Not allowed to remove this member");
        }
        members.delete(m);
        members.flush();
        access.audit(wsId, actor, "MEMBER_REMOVED", "USER", target);
        cache.evictAfterCommit(CacheService.workspaceKey(wsId));
    }

    private WorkspaceSummary summary(Workspace w) {
        return new WorkspaceSummary(w.id, w.name, w.ownerId, members.countByWorkspaceId(w.id), projects.countByWorkspaceId(w.id),
                w.createdAt, w.updatedAt, w.version);
    }
}
