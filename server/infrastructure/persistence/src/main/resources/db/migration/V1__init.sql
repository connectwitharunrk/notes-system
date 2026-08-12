-- ===========================================================================
-- Notes System - initial schema
--
-- Design notes that are easy to get wrong later:
--   * notes.id is CLIENT-generated (UUIDv7). Offline creation must not need a
--     server round trip, so the server never assigns note ids.
--   * users.change_counter is the per-user monotonic sync sequence. It is
--     incremented under this row's lock so that sequence order == commit order.
--     See docs/ARCHITECTURE.md section 7.
--   * client_* timestamps are DISPLAY ONLY. Device clocks are untrusted and
--     must never drive ordering or conflict resolution.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Plain TEXT with a lower() unique index rather than CITEXT: the domain
    -- already normalises addresses to lowercase, and this avoids binding a
    -- Java String to a non-standard column type through Hibernate.
    email           TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    password_hash   TEXT        NOT NULL,
    email_verified  BOOLEAN     NOT NULL DEFAULT FALSE,

    -- Per-user monotonic sync sequence. Every note write bumps this under a row
    -- lock, which is what makes the pull cursor hole-free.
    change_counter  BIGINT      NOT NULL DEFAULT 0,
    -- Oldest change_seq still resolvable. A device whose cursor is below this
    -- has missed purged tombstones and must perform a full resync.
    tombstone_floor BIGINT      NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT users_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT users_change_counter_non_negative CHECK (change_counter >= 0)
);

-- Case-insensitive, and partial so a soft-deleted account frees its email for reuse.
CREATE UNIQUE INDEX users_email_uk ON users (lower(email)) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- devices
--
-- Stable per-installation identity. Needed for conflict attribution ("edited on
-- your iPad") and for per-device session revocation.
-- ---------------------------------------------------------------------------
CREATE TABLE devices (
    id           UUID        PRIMARY KEY,          -- client-generated
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform     TEXT        NOT NULL,             -- ANDROID | IOS | DESKTOP | UNKNOWN
    display_name TEXT,
    app_version  TEXT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT devices_platform_valid
        CHECK (platform IN ('ANDROID', 'IOS', 'DESKTOP', 'UNKNOWN'))
);

CREATE INDEX devices_user_idx ON devices (user_id);

-- ---------------------------------------------------------------------------
-- refresh_tokens
--
-- Opaque 256-bit random tokens, stored ONLY as SHA-256 hashes. Rotating, with
-- a family_id: presenting an already-revoked token means it leaked, so the
-- whole family is revoked.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id   UUID        REFERENCES devices (id) ON DELETE SET NULL,
    token_hash  TEXT        NOT NULL,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    user_agent  TEXT,
    -- TEXT rather than INET: this is audit metadata we only ever display, and
    -- INET would need an explicit cast on every JDBC bind for no gain.
    ip_address  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX refresh_tokens_hash_uk   ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_active_idx  ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_tokens_family_idx       ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expiry_idx       ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- password_reset_tokens
-- ---------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX password_reset_tokens_hash_uk ON password_reset_tokens (token_hash);
CREATE INDEX password_reset_tokens_user_idx       ON password_reset_tokens (user_id);

-- ---------------------------------------------------------------------------
-- notes
-- ---------------------------------------------------------------------------
CREATE TABLE notes (
    id                UUID        PRIMARY KEY,     -- CLIENT-generated UUIDv7
    user_id           UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    title             TEXT        NOT NULL DEFAULT '',
    content           TEXT        NOT NULL DEFAULT '',
    content_type      TEXT        NOT NULL DEFAULT 'PLAIN',
    color             TEXT,
    is_pinned         BOOLEAN     NOT NULL DEFAULT FALSE,
    is_archived       BOOLEAN     NOT NULL DEFAULT FALSE,
    is_deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_index        DOUBLE PRECISION,

    -- Untrusted client wall-clock. Shown to the user; never used for ordering.
    client_created_at TIMESTAMPTZ NOT NULL,
    client_updated_at TIMESTAMPTZ NOT NULL,
    -- Server-authoritative.
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,

    version           BIGINT      NOT NULL DEFAULT 1,   -- optimistic concurrency
    change_seq        BIGINT      NOT NULL,             -- per-user cursor position
    content_hash      TEXT        NOT NULL,             -- SHA-256(title || 0x00 || content)

    last_modified_by  UUID        REFERENCES devices (id) ON DELETE SET NULL,
    conflict_of       UUID        REFERENCES notes (id) ON DELETE SET NULL,

    CONSTRAINT notes_version_positive    CHECK (version > 0),
    CONSTRAINT notes_change_seq_positive CHECK (change_seq > 0),
    CONSTRAINT notes_content_type_valid  CHECK (content_type IN ('PLAIN', 'MARKDOWN', 'RICH')),
    -- A tombstone must carry its deletion time, and vice versa.
    CONSTRAINT notes_deleted_consistent
        CHECK ((is_deleted AND deleted_at IS NOT NULL) OR (NOT is_deleted AND deleted_at IS NULL))
);

-- The sync cursor index. UNIQUE because two notes of the same user sharing a
-- change_seq would make the cursor ambiguous and could skip a row.
CREATE UNIQUE INDEX notes_user_change_seq_uk ON notes (user_id, change_seq);

-- Note-list query path: active notes, pinned first, newest first.
CREATE INDEX notes_list_idx
    ON notes (user_id, is_archived, is_pinned DESC, client_updated_at DESC)
    WHERE is_deleted = FALSE;

-- Full-text search. 'simple' rather than 'english': notes are multilingual and
-- stemming the wrong language is worse than not stemming at all.
CREATE INDEX notes_fts_idx ON notes
    USING GIN (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')));

-- Tombstone purge job scan path.
CREATE INDEX notes_tombstone_idx ON notes (deleted_at) WHERE is_deleted = TRUE;

CREATE INDEX notes_conflict_of_idx ON notes (conflict_of) WHERE conflict_of IS NOT NULL;
