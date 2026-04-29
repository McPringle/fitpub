-- Track imported Komoot activities separately from the core activities table.
--
-- This keeps the import-specific state isolated and allows all import-related
-- columns to be strictly non-nullable.

CREATE TABLE komoot_imports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_id UUID NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    komoot_activity_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_komoot_imports_activity_id UNIQUE (activity_id),
    CONSTRAINT uk_komoot_imports_user_komoot_activity_id UNIQUE (user_id, komoot_activity_id)
);

CREATE INDEX idx_komoot_imports_user_id
    ON komoot_imports(user_id);

CREATE INDEX idx_komoot_imports_komoot_activity_id
    ON komoot_imports(komoot_activity_id);

COMMENT ON TABLE komoot_imports IS
    'Internal mapping between FitPub activities and their originating Komoot activities';
