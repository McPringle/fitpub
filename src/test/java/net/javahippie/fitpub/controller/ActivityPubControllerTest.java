package net.javahippie.fitpub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.security.HttpSignatureValidator;
import net.javahippie.fitpub.service.ActivityImageService;
import net.javahippie.fitpub.service.FederationService;
import net.javahippie.fitpub.service.InboxProcessor;
import net.javahippie.fitpub.service.WorkoutDataPayloadBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityPubController Tests")
class ActivityPubControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private ActivityImageService activityImageService;

    @Mock
    private InboxProcessor inboxProcessor;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private HttpSignatureValidator signatureValidator;

    @Mock
    private FederationService federationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private WorkoutDataPayloadBuilder workoutDataPayloadBuilder;

    @InjectMocks
    private ActivityPubController controller;

    private UUID activityId;
    private UUID userId;
    private Activity activity;
    private User user;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        activityId = UUID.randomUUID();
        userId = UUID.randomUUID();
        createdAt = LocalDateTime.of(2026, 5, 2, 9, 24, 50, 921_241_000);

        ReflectionTestUtils.setField(controller, "baseUrl", "https://fitpub.example");

        activity = Activity.builder()
            .id(activityId)
            .userId(userId)
            .activityType(Activity.ActivityType.RUN)
            .title("Lunch Run")
            .description("Sunny run")
            .visibility(Activity.Visibility.PUBLIC)
            .totalDistance(BigDecimal.valueOf(5000))
            .totalDurationSeconds(1800L)
            .createdAt(createdAt)
            .build();

        user = User.builder()
            .id(userId)
            .username("JaneDoe")
            .email("janedoe@example.com")
            .publicKey("public-key")
            .privateKey("private-key")
            .build();
    }

    @Test
    @DisplayName("Should serialize activity published timestamp with timezone")
    void getActivity_ShouldSerializePublishedTimestampWithTimezone() {
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(activityImageService.getActivityImageFile(activityId)).thenReturn(new File("/definitely/nonexistent-fitpub-test-image"));

        ResponseEntity<Map<String, Object>> response = controller.getActivity(activityId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("published"))
            .isEqualTo(createdAt.atOffset(ZoneOffset.UTC).toInstant().toString());
    }

    @Test
    @DisplayName("Should include workoutData and FitPub context terms in activity note")
    void getActivity_ShouldIncludeWorkoutDataAndExtendedContext() {
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(activityImageService.getActivityImageFile(activityId)).thenReturn(new File("/definitely/nonexistent-fitpub-test-image"));
        when(workoutDataPayloadBuilder.build(activity)).thenReturn(Map.of(
            "activityType", "RUN",
            "description", "Sunny run",
            "distance", 5000L,
            "duration", "PT30M",
            "averagePace", "PT6M",
            "route", Map.of(
                "type", "FeatureCollection",
                "features", List.of()
            )
        ));

        ResponseEntity<Map<String, Object>> response = controller.getActivity(activityId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("workoutData")).isEqualTo(Map.of(
            "activityType", "RUN",
            "description", "Sunny run",
            "distance", 5000L,
            "duration", "PT30M",
            "averagePace", "PT6M",
            "route", Map.of(
                "type", "FeatureCollection",
                "features", List.of()
            )
        ));

        @SuppressWarnings("unchecked")
        List<Object> context = (List<Object>) response.getBody().get("@context");
        assertThat(context).hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = (Map<String, Object>) context.get(1);
        assertThat(extensions)
            .containsEntry("fitpub", "https://fitpub.social/ns#")
            .containsEntry("workoutData", "fitpub:workoutData")
            .containsEntry("route", "fitpub:route");
    }
}
