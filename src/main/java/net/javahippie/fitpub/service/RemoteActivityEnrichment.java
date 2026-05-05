package net.javahippie.fitpub.service;

import lombok.Builder;
import org.locationtech.jts.geom.LineString;

/**
 * FitPub-specific remote activity details fetched from the sender's API.
 */
@Builder
public record RemoteActivityEnrichment(
    String activityType,
    String title,
    String description,
    Long totalDistance,
    Long totalDurationSeconds,
    Integer elevationGain,
    Long averagePaceSeconds,
    Integer averageHeartRate,
    Double maxSpeed,
    Double averageSpeed,
    Integer calories,
    LineString simplifiedTrack
) {
}
