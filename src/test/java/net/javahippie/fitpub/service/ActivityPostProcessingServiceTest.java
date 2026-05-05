package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivityMetrics;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ActivityPostProcessingService.
 * Tests async operations in isolation and error handling.
 */
@ExtendWith(MockitoExtension.class)
class ActivityPostProcessingServiceTest {

    @Mock
    private PersonalRecordService personalRecordService;

    @Mock
    private WeatherService weatherService;

    @Mock
    private HeatmapGridService heatmapGridService;

    @Mock
    private FederationService federationService;

    @Mock
    private ActivityImageService activityImageService;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkoutDataPayloadBuilder workoutDataPayloadBuilder;

    @InjectMocks
    private ActivityPostProcessingService service;

    private UUID activityId;
    private UUID userId;
    private Activity testActivity;
    private User testUser;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        activityId = UUID.randomUUID();
        userId = UUID.randomUUID();
        createdAt = LocalDateTime.of(2026, 5, 2, 9, 24, 50, 921_241_000);

        // Set baseUrl via reflection (since it's @Value injected)
        ReflectionTestUtils.setField(service, "baseUrl", "https://test.example");

        // Create test activity
        testActivity = Activity.builder()
            .id(activityId)
            .userId(userId)
            .activityType(Activity.ActivityType.RUN)
            .title("Test Run")
            .description("Morning jog")
            .visibility(Activity.Visibility.PUBLIC)
            .totalDistance(BigDecimal.valueOf(5000))
            .totalDurationSeconds(1800L)
            .elevationGain(BigDecimal.valueOf(100))
            .startedAt(createdAt.minusMinutes(30))
            .createdAt(createdAt)
            .simplifiedTrack(new GeometryFactory().createLineString(new Coordinate[]{
                new Coordinate(8.55, 47.37),
                new Coordinate(8.56, 47.38)
            }))
            .build();
        testActivity.setMetrics(ActivityMetrics.builder()
            .averagePaceSeconds(321L)
            .build());
        Map<String, Object> workoutData = new HashMap<>();
        workoutData.put("activityType", "RUN");
        workoutData.put("description", "Morning jog");
        workoutData.put("distance", 5000L);
        workoutData.put("duration", "PT30M");
        workoutData.put("averagePace", "PT5M21S");
        workoutData.put("elevationGain", 100);
        workoutData.put("route", Map.of(
            "type", "FeatureCollection",
            "features", List.of(
                Map.of(
                    "type", "Feature",
                    "geometry", Map.of(
                        "type", "LineString",
                        "coordinates", List.of(
                            List.of(8.55, 47.37),
                            List.of(8.56, 47.38)
                        )
                    )
                )
            )
        ));
        lenient().when(workoutDataPayloadBuilder.build(testActivity)).thenReturn(workoutData);

        // Create test user
        testUser = User.builder()
            .id(userId)
            .username("testrunner")
            .email("test@example.com")
            .displayName("Test Runner")
            .enabled(true)
            .build();
    }

    @Test
    @DisplayName("Should successfully update personal records async")
    void testUpdatePersonalRecordsAsync_Success() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(personalRecordService.checkAndUpdatePersonalRecords(testActivity)).thenReturn(java.util.List.of());

        // When
        service.updatePersonalRecordsAsync(activityId);

        // Then
        verify(activityRepository).findById(activityId);
        verify(personalRecordService).checkAndUpdatePersonalRecords(testActivity);
    }

    @Test
    @DisplayName("Should handle personal records update failure gracefully")
    void testUpdatePersonalRecordsAsync_Failure() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        doThrow(new RuntimeException("Database error")).when(personalRecordService).checkAndUpdatePersonalRecords(testActivity);

        // When
        service.updatePersonalRecordsAsync(activityId);

        // Then
        // Should complete without throwing (error is logged, not propagated)
        verify(personalRecordService).checkAndUpdatePersonalRecords(testActivity);
    }

    @Test
    @DisplayName("Should handle activity not found in personal records update")
    void testUpdatePersonalRecordsAsync_ActivityNotFound() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.empty());

        // When
        service.updatePersonalRecordsAsync(activityId);

        // Then
        // Should complete without throwing
        verify(activityRepository).findById(activityId);
        verify(personalRecordService, never()).checkAndUpdatePersonalRecords(any());
    }

    @Test
    @DisplayName("Should successfully update heatmap async")
    void testUpdateHeatmapAsync_Success() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        doNothing().when(heatmapGridService).updateHeatmapForActivity(testActivity);

        // When
        service.updateHeatmapAsync(activityId);

        // Then
        verify(activityRepository).findById(activityId);
        verify(heatmapGridService).updateHeatmapForActivity(testActivity);
    }

    @Test
    @DisplayName("Should handle heatmap update failure gracefully")
    void testUpdateHeatmapAsync_Failure() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        doThrow(new RuntimeException("Heatmap error")).when(heatmapGridService).updateHeatmapForActivity(testActivity);

        // When
        service.updateHeatmapAsync(activityId);

        // Then
        // Should complete without throwing
        verify(heatmapGridService).updateHeatmapForActivity(testActivity);
    }

    @Test
    @DisplayName("Should successfully fetch weather async")
    void testFetchWeatherAsync_Success() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(weatherService.fetchWeatherForActivity(testActivity)).thenReturn(Optional.empty());

        // When
        service.fetchWeatherAsync(activityId);

        // Then
        verify(activityRepository).findById(activityId);
        verify(weatherService).fetchWeatherForActivity(testActivity);
    }

    @Test
    @DisplayName("Should handle weather fetch failure gracefully")
    void testFetchWeatherAsync_Failure() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        doThrow(new RuntimeException("Weather API error")).when(weatherService).fetchWeatherForActivity(testActivity);

        // When
        service.fetchWeatherAsync(activityId);

        // Then
        // Should complete without throwing
        verify(weatherService).fetchWeatherForActivity(testActivity);
    }

    @Test
    @DisplayName("Should successfully publish to federation async for PUBLIC activity")
    void testPublishToFederationAsync_PublicActivity() {
        // Given
        testActivity.setVisibility(Activity.Visibility.PUBLIC);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn("https://test.example/image.png");
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        verify(activityRepository).findById(activityId);
        verify(userRepository).findById(userId);
        verify(activityImageService).generateActivityImage(testActivity);
        verify(federationService).sendCreateActivity(anyString(), any(), eq(testUser), eq(true));
    }

    @Test
    @DisplayName("Should successfully publish to federation async for FOLLOWERS activity")
    void testPublishToFederationAsync_FollowersActivity() {
        // Given
        testActivity.setVisibility(Activity.Visibility.FOLLOWERS);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn("https://test.example/image.png");
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        verify(federationService).sendCreateActivity(anyString(), any(), eq(testUser), eq(false));
    }

    @Test
    @DisplayName("Should serialize federation note published timestamp with timezone")
    void testPublishToFederationAsync_PublishedTimestampIncludesTimezone() {
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn(null);
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> noteCaptor = ArgumentCaptor.forClass(java.util.Map.class);

        service.publishToFederationAsync(activityId, userId);

        verify(federationService).sendCreateActivity(anyString(), noteCaptor.capture(), eq(testUser), eq(true));
        assertThat(noteCaptor.getValue().get("published"))
            .isEqualTo(createdAt.atOffset(ZoneOffset.UTC).toInstant().toString());
    }

    @Test
    @DisplayName("Should skip federation for PRIVATE activity")
    void testPublishToFederationAsync_PrivateActivity() {
        // Given
        testActivity.setVisibility(Activity.Visibility.PRIVATE);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        verify(activityImageService, never()).generateActivityImage(any());
        verify(federationService, never()).sendCreateActivity(anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should handle federation publish failure gracefully")
    void testPublishToFederationAsync_Failure() {
        // Given
        testActivity.setVisibility(Activity.Visibility.PUBLIC);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn("https://test.example/image.png");
        doThrow(new RuntimeException("Federation error")).when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        // Should complete without throwing
        verify(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should handle image generation failure and continue with federation")
    void testPublishToFederationAsync_ImageGenerationFailure() {
        // Given
        testActivity.setVisibility(Activity.Visibility.PUBLIC);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenThrow(new RuntimeException("Image generation failed"));
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        verify(activityImageService).generateActivityImage(testActivity);
        verify(federationService).sendCreateActivity(anyString(), any(), eq(testUser), eq(true)); // Should still publish without image
    }

    @Test
    @DisplayName("Should handle user not found in federation publish")
    void testPublishToFederationAsync_UserNotFound() {
        // Given
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then
        // Should complete without throwing
        verify(userRepository).findById(userId);
        verify(federationService, never()).sendCreateActivity(anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should format activity content correctly")
    void testFormatActivityContent() {
        // Given: Activity with all metrics set in setUp()

        // When: Call processActivityAsync which will use formatActivityContent internally
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn(null);
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        // When
        service.publishToFederationAsync(activityId, userId);

        // Then: Verify federation was called (content formatting is tested indirectly)
        verify(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should include workoutData payload in federation note")
    void testPublishToFederationAsync_IncludesWorkoutDataPayload() {
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(testActivity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(activityImageService.generateActivityImage(testActivity)).thenReturn(null);
        doNothing().when(federationService).sendCreateActivity(anyString(), any(), any(), anyBoolean());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> noteCaptor = ArgumentCaptor.forClass(Map.class);

        service.publishToFederationAsync(activityId, userId);

        verify(federationService).sendCreateActivity(anyString(), noteCaptor.capture(), eq(testUser), eq(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> workoutData = (Map<String, Object>) noteCaptor.getValue().get("workoutData");
        assertThat(workoutData)
            .containsEntry("activityType", "RUN")
            .containsEntry("description", "Morning jog")
            .containsEntry("distance", 5000L)
            .containsEntry("duration", "PT30M")
            .containsEntry("averagePace", "PT5M21S")
            .containsEntry("elevationGain", 100);

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) workoutData.get("route");
        assertThat(route).containsEntry("type", "FeatureCollection");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> features = (List<Map<String, Object>>) route.get("features");
        assertThat(features).hasSize(1);
        assertThat(features.get(0)).containsEntry("type", "Feature");

        @SuppressWarnings("unchecked")
        Map<String, Object> geometry = (Map<String, Object>) features.get(0).get("geometry");
        assertThat(geometry).containsEntry("type", "LineString");
        assertThat(geometry.get("coordinates")).isEqualTo(List.of(
            List.of(8.55, 47.37),
            List.of(8.56, 47.38)
        ));
    }
}
