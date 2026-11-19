package io.cloudscale;

import io.cloudscale.Entities.Role;
import io.cloudscale.Paging.PageResponse;
import io.cloudscale.ProjectService.ProjectView;
import io.cloudscale.WorkspaceService.MemberView;
import io.cloudscale.WorkspaceService.WorkspaceView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspaces, members and projects")
public class WorkspaceController {
    public record WorkspaceListItem(UUID id, String name, UUID ownerId, java.time.Instant createdAt, java.time.Instant updatedAt, Long version) {}

    public record CreateWorkspace(@NotBlank @Size(max = 100) String name) {}

    public record UpdateWorkspace(@NotBlank @Size(max = 100) String name, Long version) {}

    public record AddMember(@NotBlank @Email String email, @NotNull Role role) {}

    public record ChangeRole(@NotNull Role role) {}

    public record CreateProject(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {}

    public record UpdateProject(@Size(min = 1, max = 100) String name, @Size(max = 1000) String description, String status) {}

    private final WorkspaceService workspaces;
    private final ProjectService projects;

    public WorkspaceController(WorkspaceService workspaces, ProjectService projects) {
        this.workspaces = workspaces;
        this.projects = projects;
    }

    private static UUID uid(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // ---- workspaces ----

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateWorkspace req) {
        return workspaces.create(uid(jwt), req.name());
    }

    @GetMapping
    public PageResponse<WorkspaceListItem> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String sort) {
        var p = workspaces.list(uid(jwt), Paging.of(page, size, sort, WorkspaceService.SORTABLE, "createdAt"));
        return PageResponse.of(p, w -> new WorkspaceListItem(w.id, w.name, w.ownerId, w.createdAt, w.updatedAt, w.version));
    }

    @GetMapping("/{id}")
    public WorkspaceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workspaces.get(id, uid(jwt));
    }

    @PatchMapping("/{id}")
    public WorkspaceView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateWorkspace req) {
        return workspaces.update(id, uid(jwt), req.name(), req.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        workspaces.delete(id, uid(jwt));
    }

    // ---- members ----

    @GetMapping("/{id}/members")
    public List<MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workspaces.listMembers(id, uid(jwt));
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody AddMember req) {
        return workspaces.addMember(id, uid(jwt), req.email(), req.role());
    }

    @PatchMapping("/{id}/members/{userId}")
    public MemberView changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId, @Valid @RequestBody ChangeRole req) {
        return workspaces.changeRole(id, uid(jwt), userId, req.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
        workspaces.removeMember(id, uid(jwt), userId);
    }

    // ---- projects ----

    @PostMapping("/{id}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView createProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CreateProject req) {
        return projects.create(id, uid(jwt), req.name(), req.description());
    }

    @GetMapping("/{id}/projects")
    public PageResponse<ProjectView> listProjects(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String sort, @RequestParam(required = false) String status) {
        var p = projects.list(id, uid(jwt), status, Paging.of(page, size, sort, ProjectService.SORTABLE, "createdAt"));
        return PageResponse.of(p, ProjectView::of);
    }

    @GetMapping("/{id}/projects/{projectId}")
    public ProjectView getProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID projectId) {
        return projects.get(id, projectId, uid(jwt));
    }

    @PatchMapping("/{id}/projects/{projectId}")
    public ProjectView updateProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID projectId, @Valid @RequestBody UpdateProject req) {
        return projects.update(id, projectId, uid(jwt), req.name(), req.description(), req.status());
    }

    @DeleteMapping("/{id}/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID projectId) {
        projects.delete(id, projectId, uid(jwt));
    }
}
