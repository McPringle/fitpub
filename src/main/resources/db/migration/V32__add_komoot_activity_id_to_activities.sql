-- Add optional internal reference to the originating Komoot activity.
--
-- This field is only used for import matching and deduplication. It is not
-- intended for public display or API exposure.

ALTER TABLE activities
    ADD COLUMN komoot_activity_id BIGINT;

-- A Komoot activity may only be imported once per local user.
CREATE UNIQUE INDEX idx_activities_user_komoot_activity_id
    ON activities(user_id, komoot_activity_id)
    WHERE komoot_activity_id IS NOT NULL;

COMMENT ON COLUMN activities.komoot_activity_id IS
    'Optional internal Komoot activity ID used for import matching and deduplication';
