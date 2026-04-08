-- Migration V31: Convert one-leg cadence to steps-per-minute for foot activities.
--
-- FIT files and Garmin/TrainingPeaks GPX extensions store cadence as one-leg
-- revolutions-per-minute regardless of sport. For cycling that's correct (it's
-- pedal RPM). For running / walking / hiking, every consumer (Strava, Garmin
-- Connect, etc.) doubles the value to display "steps per minute" — the
-- convention runners actually expect.
--
-- The application code is fixed in FitParser and GpxParser to apply the ×2 at
-- ingestion. This migration brings existing rows in line with that contract.
--
-- Three places to update:
--   1. activity_metrics.average_cadence — bulk UPDATE
--   2. activity_metrics.max_cadence     — bulk UPDATE
--   3. activities.track_points_json     — per-row JSONB rewrite of every point's cadence
--
-- Foot activity types: RUN, WALK, HIKE, MOUNTAINEERING. Other types are untouched.

-- ----------------------------------------------------------------------------
-- Step 1: Double the session-level cadence aggregates.
-- ----------------------------------------------------------------------------

UPDATE activity_metrics am
SET average_cadence = average_cadence * 2
FROM activities a
WHERE am.activity_id = a.id
  AND a.activity_type IN ('RUN', 'WALK', 'HIKE', 'MOUNTAINEERING')
  AND am.average_cadence IS NOT NULL;

UPDATE activity_metrics am
SET max_cadence = max_cadence * 2
FROM activities a
WHERE am.activity_id = a.id
  AND a.activity_type IN ('RUN', 'WALK', 'HIKE', 'MOUNTAINEERING')
  AND am.max_cadence IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Step 2: Double the per-track-point cadence inside track_points_json.
--
-- track_points_json is a JSONB array of objects shaped like
--   {"timestamp": "...", "latitude": ..., "cadence": 85, ...}
--
-- For each row of a foot activity, rebuild the array by walking each element
-- with jsonb_array_elements and applying jsonb_set when 'cadence' is present
-- and non-null. Untouched points (no cadence, or null cadence) pass through
-- unchanged. The whole expression is wrapped in a single UPDATE so the row
-- write is atomic.
-- ----------------------------------------------------------------------------

UPDATE activities a
SET track_points_json = (
    SELECT jsonb_agg(
        CASE
            WHEN point ? 'cadence'
                 AND jsonb_typeof(point->'cadence') = 'number'
                THEN jsonb_set(point, '{cadence}', to_jsonb((point->>'cadence')::int * 2))
            ELSE point
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(a.track_points_json) WITH ORDINALITY arr(point, ord)
)
WHERE a.activity_type IN ('RUN', 'WALK', 'HIKE', 'MOUNTAINEERING')
  AND a.track_points_json IS NOT NULL
  AND jsonb_typeof(a.track_points_json) = 'array'
  AND jsonb_array_length(a.track_points_json) > 0;
