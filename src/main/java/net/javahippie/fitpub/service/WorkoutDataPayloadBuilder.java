package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import net.javahippie.fitpub.model.dto.ActivityDTO;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivityMetrics;
import net.javahippie.fitpub.model.entity.PrivacyZone;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the proprietary workoutData payload for outbound ActivityPub Notes.
 */
@Service
@RequiredArgsConstructor
public class WorkoutDataPayloadBuilder {

    private final PrivacyZoneService privacyZoneService;
    private final TrackPrivacyFilter trackPrivacyFilter;

    public Map<String, Object> build(Activity activity) {
        Map<String, Object> workoutData = new HashMap<>();
        workoutData.put("activityType", activity.getActivityType().name());

        if (activity.getDescription() != null && !activity.getDescription().isBlank()) {
            workoutData.put("description", activity.getDescription());
        }
        if (activity.getTotalDistance() != null) {
            workoutData.put("distance", activity.getTotalDistance().longValue());
        }
        if (activity.getTotalDurationSeconds() != null) {
            workoutData.put("duration", Duration.ofSeconds(activity.getTotalDurationSeconds()).toString());
        }
        if (activity.getElevationGain() != null) {
            workoutData.put("elevationGain", activity.getElevationGain().intValue());
        }

        ActivityMetrics metrics = activity.getMetrics();
        if (metrics != null) {
            if (metrics.getAveragePaceSeconds() != null) {
                workoutData.put("averagePace", Duration.ofSeconds(metrics.getAveragePaceSeconds()).toString());
            }
            if (metrics.getAverageHeartRate() != null) {
                workoutData.put("averageHeartRate", metrics.getAverageHeartRate());
            }
            if (metrics.getAverageSpeed() != null) {
                workoutData.put("averageSpeed", metrics.getAverageSpeed().doubleValue());
            }
            if (metrics.getMaxSpeed() != null) {
                workoutData.put("maxSpeed", metrics.getMaxSpeed().doubleValue());
            }
            if (metrics.getCalories() != null) {
                workoutData.put("calories", metrics.getCalories());
            }
        }

        Map<String, Object> route = buildRoutePayload(activity);
        if (route != null) {
            workoutData.put("route", route);
        }

        return workoutData;
    }

    private Map<String, Object> buildRoutePayload(Activity activity) {
        List<PrivacyZone> privacyZones = privacyZoneService.getActivePrivacyZones(activity.getUserId());
        ActivityDTO dto = ActivityDTO.fromEntityWithFiltering(activity, null, privacyZones, trackPrivacyFilter);

        if (dto.getSimplifiedTrack() == null) {
            return null;
        }

        Map<String, Object> feature = new HashMap<>();
        feature.put("type", "Feature");
        feature.put("geometry", dto.getSimplifiedTrack());

        Map<String, Object> featureCollection = new HashMap<>();
        featureCollection.put("type", "FeatureCollection");
        featureCollection.put("features", List.of(feature));
        return featureCollection;
    }
}
