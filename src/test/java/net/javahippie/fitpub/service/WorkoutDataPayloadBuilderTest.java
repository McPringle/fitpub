package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivityMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkoutDataPayloadBuilder Tests")
class WorkoutDataPayloadBuilderTest {

    @Mock
    private PrivacyZoneService privacyZoneService;

    @Mock
    private TrackPrivacyFilter trackPrivacyFilter;

    @InjectMocks
    private WorkoutDataPayloadBuilder builder;

    private UUID userId;
    private Activity activity;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        activity = Activity.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .activityType(Activity.ActivityType.RUN)
            .description("Morning jog")
            .visibility(Activity.Visibility.PUBLIC)
            .totalDistance(BigDecimal.valueOf(5000))
            .totalDurationSeconds(1800L)
            .elevationGain(BigDecimal.valueOf(100))
            .simplifiedTrack(new GeometryFactory().createLineString(new Coordinate[]{
                new Coordinate(8.55, 47.37),
                new Coordinate(8.56, 47.38)
            }))
            .build();
        activity.setMetrics(ActivityMetrics.builder()
            .averagePaceSeconds(321L)
            .averageHeartRate(150)
            .averageSpeed(BigDecimal.valueOf(10.4))
            .maxSpeed(BigDecimal.valueOf(14.2))
            .calories(420)
            .build());
    }

    @Test
    @DisplayName("Should build workoutData payload with route and metrics")
    void build_ShouldIncludeWorkoutDataRouteAndMetrics() {
        when(privacyZoneService.getActivePrivacyZones(userId)).thenReturn(List.of());

        Map<String, Object> workoutData = builder.build(activity);

        assertThat(workoutData)
            .containsEntry("activityType", "RUN")
            .containsEntry("description", "Morning jog")
            .containsEntry("distance", 5000L)
            .containsEntry("duration", "PT30M")
            .containsEntry("elevationGain", 100)
            .containsEntry("averagePace", "PT5M21S")
            .containsEntry("averageHeartRate", 150)
            .containsEntry("averageSpeed", 10.4)
            .containsEntry("maxSpeed", 14.2)
            .containsEntry("calories", 420);

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) workoutData.get("route");
        assertThat(route).containsEntry("type", "FeatureCollection");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> features = (List<Map<String, Object>>) route.get("features");
        assertThat(features).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> geometry = (Map<String, Object>) features.get(0).get("geometry");
        assertThat(geometry).containsEntry("type", "LineString");
        assertThat(geometry.get("coordinates")).isEqualTo(List.of(
            List.of(8.55, 47.37),
            List.of(8.56, 47.38)
        ));
    }
}
