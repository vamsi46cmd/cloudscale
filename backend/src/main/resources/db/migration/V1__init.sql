CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE workspaces (
    id         uuid PRIMARY KEY,
    name       varchar(100) NOT NULL,
    owner_id   uuid         NOT NULL REFERENCES users (id),
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE workspace_memberships (
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         varchar(10) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX memberships_user_idx ON workspace_memberships (user_id);

CREATE TABLE projects (
    id           uuid PRIMARY KEY,
    workspace_id uuid         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         varchar(100) NOT NULL,
    description  varchar(1000),
    status       varchar(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX projects_ws_created_idx ON projects (workspace_id, created_at DESC, id);

CREATE TABLE processing_jobs (
    id               uuid PRIMARY KEY,
    workspace_id     uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id       uuid        REFERENCES projects (id) ON DELETE SET NULL,
    type             varchar(30) NOT NULL,
    status           varchar(10) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    payload          jsonb       NOT NULL,
    result           jsonb,
    error            varchar(1000),
    attempt_count    int         NOT NULL DEFAULT 0,
    max_attempts     int         NOT NULL DEFAULT 3,
    run_at           timestamptz NOT NULL DEFAULT now(),
    cancel_requested boolean     NOT NULL DEFAULT false,
    idempotency_key  varchar(100),
    created_by       uuid        NOT NULL REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    completed_at     timestamptz,
    lease_expires_at timestamptz,
    duration_ms      bigint
);
-- One job per (workspace, idempotency key): duplicate submissions collapse onto the first.
CREATE UNIQUE INDEX jobs_idempotency_uq ON processing_jobs (workspace_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Partial indexes keep the claim query off the (large) set of finished jobs.
CREATE INDEX jobs_queued_idx ON processing_jobs (run_at, created_at) WHERE status = 'QUEUED';
CREATE INDEX jobs_running_lease_idx ON processing_jobs (lease_expires_at) WHERE status = 'RUNNING';
CREATE INDEX jobs_ws_created_idx ON processing_jobs (workspace_id, created_at DESC, id);

-- No FK on workspace_id: audit history must survive workspace deletion.
CREATE TABLE audit_events (
    id           uuid PRIMARY KEY,
    workspace_id uuid        NOT NULL,
    actor_id     uuid,
    action       varchar(60) NOT NULL,
    entity_type  varchar(30) NOT NULL,
    entity_id    uuid,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_ws_created_idx ON audit_events (workspace_id, created_at DESC);

-- Refresh tokens are stored only as SHA-256 hashes. family_id links a rotation chain (reuse detection).
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash char(64)    NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
