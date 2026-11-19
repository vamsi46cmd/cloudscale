package io.cloudscale;

import io.cloudscale.Entities.AppUser;
import io.cloudscale.Entities.MemberId;
import io.cloudscale.Entities.Membership;
import io.cloudscale.Entities.Project;
import io.cloudscale.Entities.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositories. Every tenant-owned lookup takes the workspace id, so a guessed id from another tenant matches nothing. */
public final class Repos {
    private Repos() {}

    public interface UserRepo extends JpaRepository<AppUser, UUID> {
        Optional<AppUser> findByEmailIgnoreCase(String email);
    }

    public interface WorkspaceRepo extends JpaRepository<Workspace, UUID> {
        @Query(value = "select w from Workspace w where w.id in (select m.workspaceId from Membership m where m.userId = :uid)",
               countQuery = "select count(w) from Workspace w where w.id in (select m.workspaceId from Membership m where m.userId = :uid)")
        Page<Workspace> findForUser(@Param("uid") UUID uid, Pageable pageable);
    }

    public interface MembershipRepo extends JpaRepository<Membership, MemberId> {
        Optional<Membership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

        long countByWorkspaceId(UUID workspaceId);
    }

    public interface ProjectRepo extends JpaRepository<Project, UUID> {
        Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

        Page<Project> findByWorkspaceId(UUID workspaceId, Pageable pageable);

        Page<Project> findByWorkspaceIdAndStatus(UUID workspaceId, String status, Pageable pageable);

        long countByWorkspaceId(UUID workspaceId);
    }
}
