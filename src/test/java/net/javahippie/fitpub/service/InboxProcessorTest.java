package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.RemoteActivity;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.CommentRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.LikeRepository;
import net.javahippie.fitpub.repository.RemoteActivityRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.UserRepository;
import org.locationtech.jts.geom.LineString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InboxProcessor Tests")
class InboxProcessorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private FederationService federationService;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RemoteActivityRepository remoteActivityRepository;

    @Mock
    private RemoteActorRepository remoteActorRepository;

    @InjectMocks
    private InboxProcessor inboxProcessor;

    private User localUser;
    private String remoteActorUri;

    @BeforeEach
    void setUp() {
        localUser = User.builder()
            .id(UUID.randomUUID())
            .username("JaneDoe")
            .email("janedoe@example.com")
            .passwordHash("irrelevant")
            .publicKey("public-key")
            .privateKey("private-key")
            .build();

        remoteActorUri = "https://fitpub.example.com/users/JohnDoe";

        ReflectionTestUtils.setField(inboxProcessor, "baseUrl", "https://fitpub.example");
    }

    @Test
    @DisplayName("Should persist remote activity when published timestamp has fractional seconds but no timezone")
    void processCreateRemoteActivity_WithPublishedTimestampWithoutTimezone_ShouldPersistRemoteActivity() {
        when(remoteActivityRepository.existsByActivityUri("https://fitpub.example.com/activities/123"))
            .thenReturn(false);
        when(federationService.fetchRemoteActor(remoteActorUri)).thenReturn(RemoteActor.builder()
            .actorUri(remoteActorUri)
            .username("JohnDoe")
            .domain("fitpub.example.com")
            .inboxUrl("https://fitpub.example.com/users/JohnDoe/inbox")
            .publicKey("public-key")
            .build());
        when(userRepository.findByUsername("JaneDoe")).thenReturn(Optional.of(localUser));
        when(followRepository.findByFollowerIdAndFollowingActorUri(localUser.getId(), remoteActorUri))
            .thenReturn(Optional.of(Follow.builder()
                .followerId(localUser.getId())
                .followingActorUri(remoteActorUri)
                .status(Follow.FollowStatus.ACCEPTED)
                .build()));

        Map<String, Object> note = Map.of(
            "id", "https://fitpub.example.com/activities/123",
            "type", "Note",
            "name", "Lunch Run",
            "content", "<p>Sunny run</p>",
            "published", "2026-05-02T09:24:50.921241",
            "to", List.of("https://www.w3.org/ns/activitystreams#Public")
        );

        Map<String, Object> activity = Map.of(
            "type", "Create",
            "actor", remoteActorUri,
            "object", note
        );

        ArgumentCaptor<net.javahippie.fitpub.model.entity.RemoteActivity> remoteActivityCaptor =
            ArgumentCaptor.forClass(net.javahippie.fitpub.model.entity.RemoteActivity.class);

        inboxProcessor.processActivity("JaneDoe", activity);

        verify(remoteActivityRepository).existsByActivityUri("https://fitpub.example.com/activities/123");
        verify(federationService).fetchRemoteActor(remoteActorUri);
        verify(remoteActivityRepository).save(remoteActivityCaptor.capture());

        assertThat(remoteActivityCaptor.getValue().getPublishedAt())
            .isEqualTo(Instant.parse("2026-05-02T09:24:50.921241Z"));
    }

    @Test
    @DisplayName("Should prefer workoutData fields over legacy content parsing")
    void processCreateRemoteActivity_WithWorkoutDataPayload_ShouldPreferWorkoutDataFields() {
        when(remoteActivityRepository.existsByActivityUri("https://fitpub.example.com/activities/456"))
            .thenReturn(false);
        when(federationService.fetchRemoteActor(remoteActorUri)).thenReturn(RemoteActor.builder()
            .actorUri(remoteActorUri)
            .username("JohnDoe")
            .domain("fitpub.example.com")
            .inboxUrl("https://fitpub.example.com/users/JohnDoe/inbox")
            .publicKey("public-key")
            .build());
        when(userRepository.findByUsername("JaneDoe")).thenReturn(Optional.of(localUser));
        when(followRepository.findByFollowerIdAndFollowingActorUri(localUser.getId(), remoteActorUri))
            .thenReturn(Optional.of(Follow.builder()
                .followerId(localUser.getId())
                .followingActorUri(remoteActorUri)
                .status(Follow.FollowStatus.ACCEPTED)
                .build()));

        Map<String, Object> workoutData = new HashMap<>();
        workoutData.put("activityType", "RUN");
        workoutData.put("description", "Direct workoutData description");
        workoutData.put("distance", 9800L);
        workoutData.put("duration", "PT41M9S");
        workoutData.put("averagePace", "PT4M12S");
        workoutData.put("elevationGain", 123);
        workoutData.put("route", Map.of(
            "type", "FeatureCollection",
            "features", List.of(Map.of(
                "type", "Feature",
                "geometry", Map.of(
                    "type", "LineString",
                    "coordinates", List.of(
                        List.of(8.55, 47.37),
                        List.of(8.56, 47.38),
                        List.of(8.57, 47.39)
                    )
                )
            ))
        ));

        Map<String, Object> note = Map.of(
            "id", "https://fitpub.example.com/activities/456",
            "type", "Note",
            "name", "Kraremanns Lauf 2026",
            "content", "<p>Kraremanns Lauf 2026</p><p>Run · 9.80 km · 41:09</p><p>Legacy content fallback</p>",
            "published", "2026-05-02T09:24:50.921241",
            "to", List.of("https://www.w3.org/ns/activitystreams#Public"),
            "workoutData", workoutData
        );

        Map<String, Object> activity = Map.of(
            "type", "Create",
            "actor", remoteActorUri,
            "object", note
        );

        ArgumentCaptor<RemoteActivity> remoteActivityCaptor =
            ArgumentCaptor.forClass(RemoteActivity.class);

        inboxProcessor.processActivity("JaneDoe", activity);

        verify(remoteActivityRepository).save(remoteActivityCaptor.capture());

        RemoteActivity remoteActivity = remoteActivityCaptor.getValue();
        assertThat(remoteActivity.getTitle()).isEqualTo("Kraremanns Lauf 2026");
        assertThat(remoteActivity.getDescription()).isEqualTo("Direct workoutData description");
        assertThat(remoteActivity.getActivityType()).isEqualTo("RUN");
        assertThat(remoteActivity.getTotalDistance()).isEqualTo(9800L);
        assertThat(remoteActivity.getTotalDurationSeconds()).isEqualTo(2469L);
        assertThat(remoteActivity.getAveragePaceSeconds()).isEqualTo(252L);
        assertThat(remoteActivity.getElevationGain()).isEqualTo(123);
        LineString simplifiedTrack = remoteActivity.getSimplifiedTrack();
        assertThat(simplifiedTrack).isNotNull();
        assertThat(simplifiedTrack.getNumPoints()).isEqualTo(3);
        assertThat(simplifiedTrack.getSRID()).isEqualTo(4326);
        assertThat(simplifiedTrack.getCoordinateN(0).x).isEqualTo(8.55);
        assertThat(simplifiedTrack.getCoordinateN(0).y).isEqualTo(47.37);
    }
}
