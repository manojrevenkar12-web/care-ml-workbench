-- ML App schema. De-identified data and the sealed originals live in separate tables; only the
-- vault table holds (encrypted) PHI. In production the vault table can live in a separate
-- database whose credentials only the reveal path holds.

CREATE TABLE tenants (
    tenant      text PRIMARY KEY,
    region      text NOT NULL CHECK (region IN ('EU', 'US')),
    baa_signed  boolean NOT NULL DEFAULT false
);

CREATE TABLE projects (
    id          text PRIMARY KEY,
    tenant      text NOT NULL REFERENCES tenants (tenant),
    name        text NOT NULL,
    description text NOT NULL DEFAULT ''
);

CREATE TABLE items (
    id                 text PRIMARY KEY,
    tenant             text NOT NULL REFERENCES tenants (tenant),
    project_id         text NOT NULL REFERENCES projects (id),
    facility_id        text NOT NULL,
    subject            text NOT NULL,
    recorded_at        timestamptz NOT NULL,
    asr_model          text NOT NULL,
    asr_text           text NOT NULL,
    surrogates         jsonb NOT NULL DEFAULT '[]',
    priority           double precision NOT NULL DEFAULT 0,
    reference_text     text,
    reference_entities jsonb,
    predictions        jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX items_project ON items (project_id, facility_id);

CREATE TABLE sealed_records (
    item_id     text PRIMARY KEY REFERENCES items (id),
    tenant      text NOT NULL,
    record_id   text NOT NULL,
    key_id      text NOT NULL,
    wrapped_key text NOT NULL,
    iv          text NOT NULL,
    ciphertext  text NOT NULL
);

CREATE TABLE tasks (
    id           text PRIMARY KEY,
    item_id      text NOT NULL REFERENCES items (id),
    project_id   text NOT NULL REFERENCES projects (id),
    kind         text NOT NULL CHECK (kind IN ('ANNOTATE', 'REVIEW')),
    status       text NOT NULL CHECK (status IN ('OPEN', 'LEASED', 'SUBMITTED', 'EXPIRED')),
    assignee     text,
    leased_until timestamptz,
    created_at   timestamptz NOT NULL
);
CREATE INDEX tasks_queue ON tasks (project_id, kind, status);

CREATE TABLE annotations (
    id            text PRIMARY KEY,
    task_id       text NOT NULL REFERENCES tasks (id),
    item_id       text NOT NULL REFERENCES items (id),
    annotator     text NOT NULL,
    transcript    text NOT NULL,
    entities      jsonb NOT NULL,
    submitted_at  timestamptz NOT NULL,
    seconds_spent integer NOT NULL,
    final         boolean NOT NULL DEFAULT false
);
CREATE INDEX annotations_item ON annotations (item_id, annotator);

CREATE TABLE snapshots (
    id              text PRIMARY KEY,
    project_id      text NOT NULL REFERENCES projects (id),
    version         integer NOT NULL,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL,
    manifest_sha256 text NOT NULL,
    counts          jsonb NOT NULL,
    label_counts    jsonb NOT NULL,
    excluded        jsonb NOT NULL,
    UNIQUE (project_id, version)
);

CREATE TABLE snapshot_examples (
    snapshot_id text NOT NULL REFERENCES snapshots (id),
    item_id     text NOT NULL,
    body        jsonb NOT NULL,
    PRIMARY KEY (snapshot_id, item_id)
);

CREATE TABLE jobs (
    id         text PRIMARY KEY,
    project_id text NOT NULL REFERENCES projects (id),
    status     text NOT NULL,
    created_at timestamptz NOT NULL,
    body       jsonb NOT NULL
);

CREATE TABLE models (
    name            text PRIMARY KEY,
    project_id      text NOT NULL REFERENCES projects (id),
    job_id          text NOT NULL REFERENCES jobs (id),
    artifact        text NOT NULL,
    snapshot_sha256 text NOT NULL,
    metrics         jsonb NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE TABLE quarantine (
    id        bigserial PRIMARY KEY,
    record_id text NOT NULL,
    tenant    text NOT NULL,
    reason    text NOT NULL,
    at        timestamptz NOT NULL
);

-- Append-only: the application role may INSERT and SELECT, never UPDATE or DELETE (see V2).
CREATE TABLE audit_events (
    seq       bigint PRIMARY KEY,
    at        timestamptz NOT NULL,
    actor     text NOT NULL,
    action    text NOT NULL,
    tenant    text NOT NULL,
    resource  text NOT NULL,
    detail    jsonb NOT NULL,
    prev_hash text NOT NULL,
    hash      text NOT NULL
);
CREATE INDEX audit_tenant_action ON audit_events (tenant, action, seq DESC);
