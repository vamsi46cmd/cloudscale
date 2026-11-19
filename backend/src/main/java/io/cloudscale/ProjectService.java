package io.cloudscale;

import io.cloudscale.Entities.Project;
import io.cloudscale.Entities.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    public record ProjectView(UUID id, UUID workspaceId, String name, String description, String status,
                              Instant createdAt, Instant updatedAt, Long version) {
        static ProjectView of(Project p) {
            return new ProjectView(p.id, p.workspaceId, p.name, p.description, p.status, p.createdAt, p.updatedAt, p.version);
        }
    }

    public static final Set<String> SORTABLE = Set.of("createdAt", "name", "updatedAt");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");

    private final Repos.ProjectRepo projects;
    private final Access access;
    private final CacheService cache;

    public ProjectService(Repos.ProjectRepo projects, Access access, CacheService cache) {
        this.projects = projects;
        this.access = access;
        this.cache = cache;
    }

    @Transactional
    public ProjectView create(UUID wsId, UUID userId, String name, String description) {
        access.require(wsId, userId, Role.MEMBER);
        Project p = new Project();
        p.id = UUID.randomUUID();
        p.workspaceId = wsId;
        p.name = name.trim();
        p.description = description;
        p.status = "ACTIVE";
        projects.saveAndFlush(p);
        access.audit(wsId, userId, "PROJECT_CREATED", "PROJECT", p.id);
        cache.evictAfterCommit(CacheService.workspaceKey(wsId));   // project count changed
        return ProjectView.of(p);
    }

    @Transactional(readOnly = true)
    public Page<Project> list(UUID wsId, UUID userId, String status, Pageable pageable) {
        access.role(wsId, userId);
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be ACTIVE or ARCHIVED");
        }
        return status == null ? projects.findByWorkspaceId(wsId, pageable) : projects.findByWorkspaceIdAndStatus(wsId, status, pageable);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID wsId, UUID projectId, UUID userId) {
        access.role(wsId, userId);
        String key = CacheService.projectKey(wsId, projectId);
        return cache.get(key, ProjectView.class).orElseGet(() -> {
            ProjectView v = ProjectView.of(projects.findByIdAndWorkspaceId(projectId, wsId)
                    .orElseThrow(() -> ApiException.notFound("Project not found")));
            cache.put(key, v);
            return v;
        });
    }

    @Transactional
    public ProjectView update(UUID wsId, UUID projectId, UUID userId, String name, String description, String status) {
        access.require(wsId, userId, Role.MEMBER);
        Project p = projects.findByIdAndWorkspaceId(projectId, wsId).orElseThrow(() -> ApiException.notFound("Project not found"));
        if (name != null) {
            p.name = name.trim();
        }
        if (description != null) {
            p.description = description;
        }
        if (status != null) {
            if (!STATUSES.contains(status)) {
                throw ApiException.badRequest("status must be ACTIVE or ARCHIVED");
            }
            p.status = status;
        }
        projects.saveAndFlush(p);
        access.audit(wsId, userId, "PROJECT_UPDATED", "PROJECT", projectId);
        cache.evictAfterCommit(CacheService.projectKey(wsId, projectId));
        return ProjectView.of(p);
    }

    @Transactional
    public void delete(UUID wsId, UUID projectId, UUID userId) {
        access.require(wsId, userId, Role.ADMIN);
        Project p = projects.findByIdAndWorkspaceId(projectId, wsId).orElseThrow(() -> ApiException.notFound("Project not found"));
        projects.delete(p);
        projects.flush();
        access.audit(wsId, userId, "PROJECT_DELETED", "PROJECT", projectId);
        cache.evictAfterCommit(CacheService.projectKey(wsId, projectId), CacheService.workspaceKey(wsId));
    }
}
