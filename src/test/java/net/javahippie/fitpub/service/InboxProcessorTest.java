package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.CommentRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.LikeRepository;
import net.javahippie.fitpub.repository.RemoteActivityRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.UserRepository;
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
}
