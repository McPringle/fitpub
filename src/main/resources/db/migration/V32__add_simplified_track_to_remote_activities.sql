ALTER TABLE remote_activities
    ADD COLUMN simplified_track geometry(LineString, 4326);

CREATE INDEX idx_remote_activity_simplified_track
    ON remote_activities
    USING gist (simplified_track);

COMMENT ON COLUMN remote_activities.simplified_track IS
    'Simplified remote route geometry for local map rendering';
