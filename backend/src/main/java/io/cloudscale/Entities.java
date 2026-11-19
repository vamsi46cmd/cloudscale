package io.cloudscale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** JPA entities. Optimistic locking (@Version) guards concurrent workspace/project/membership updates. */
public final class Entities {
    private Entities() {}

    public enum Role {
        MEMBER, ADMIN, OWNER;

        public boolean atLeast(Role other) {
            return ordinal() >= other.ordinal();
        }
    }

    @Entity(name = "AppUser")
    @Table(name = "users")
    public static class AppUser {
        @Id public UUID id;
        public String email;
        @Column(name = "password_hash") public String passwordHash;
        @Column(name = "created_at") public Instant createdAt;
        @Column(name = "updated_at") public Instant updatedAt;

        @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
        @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    }

    @Entity(name = "Workspace")
    @Table(name = "workspaces")
    public static class Workspace {
        @Id public UUID id;
        public String name;
        @Column(name = "owner_id") public UUID ownerId;
        @Version public Long version;
        @Column(name = "created_at") public Instant createdAt;
        @Column(name = "updated_at") public Instant updatedAt;

        @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
        @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    }

    public static class MemberId implements Serializable {
        public UUID workspaceId;
        public UUID userId;

        public MemberId() {}

        @Override public boolean equals(Object o) {
            return o instanceof MemberId m && Objects.equals(workspaceId, m.workspaceId) && Objects.equals(userId, m.userId);
        }

        @Override public int hashCode() { return Objects.hash(workspaceId, userId); }
    }

    @Entity(name = "Membership")
    @IdClass(MemberId.class)
    @Table(name = "workspace_memberships")
    public static class Membership {
        @Id @Column(name = "workspace_id") public UUID workspaceId;
        @Id @Column(name = "user_id") public UUID userId;
        @Enumerated(EnumType.STRING) public Role role;
        @Version public Long version;
        @Column(name = "created_at") public Instant createdAt;

        @PrePersist void onCreate() { createdAt = Instant.now(); }
    }

    @Entity(name = "Project")
    @Table(name = "projects")
    public static class Project {
        @Id public UUID id;
        @Column(name = "workspace_id") public UUID workspaceId;
        public String name;
        public String description;
        public String status;
        @Version public Long version;
        @Column(name = "created_at") public Instant createdAt;
        @Column(name = "updated_at") public Instant updatedAt;

        @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
        @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    }
}
