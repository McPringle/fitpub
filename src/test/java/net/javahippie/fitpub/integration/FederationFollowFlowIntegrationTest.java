package net.javahippie.fitpub.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javahippie.fitpub.config.TestcontainersConfiguration;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.service.ActivityImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.RemoteActivity;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.RemoteActivityRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.security.HttpSignatureValidator;
import net.javahippie.fitpub.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the complete federation follow flow.
 * Tests the entire workflow from following a remote user to receiving accept notifications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class FederationFollowFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private RemoteActorRepository remoteActorRepository;

    @Autowired
    private RemoteActivityRepository remoteActivityRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private HttpSignatureValidator signatureValidator;

    @MockBean
    private ActivityImageService activityImageService;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    private User testUser;
    private String authToken;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        // Generate RSA key pair for ActivityPub
        KeyPair keyPair = generateRsaKeyPair();
        String publicKey = encodePublicKey(keyPair.getPublic().getEncoded());
        String privateKey = encodePrivateKey(keyPair.getPrivate().getEncoded());

        // Create test user
        testUser = User.builder()
            .username("testuser")
            .email("test@example.com")
            .passwordHash(passwordEncoder.encode("password123"))
            .displayName("Test User")
            .publicKey(publicKey)
            .privateKey(privateKey)
            .enabled(true)
            .build();
        testUser = userRepository.save(testUser);

        // Generate JWT token
        authToken = jwtTokenProvider.createToken(testUser.getUsername());
    }

    private User createFederatedUser(String username, String email, String displayName) throws NoSuchAlgorithmException {
        KeyPair keyPair = generateRsaKeyPair();
        String publicKey = encodePublicKey(keyPair.getPublic().getEncoded());
        String privateKey = encodePrivateKey(keyPair.getPrivate().getEncoded());

        return userRepository.save(User.builder()
            .username(username)
            .email(email)
            .passwordHash(passwordEncoder.encode("password123"))
            .displayName(displayName)
            .publicKey(publicKey)
            .privateKey(privateKey)
            .enabled(true)
            .build());
    }

    private KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    private String encodePublicKey(byte[] keyBytes) {
        String base64 = Base64.getEncoder().encodeToString(keyBytes);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    private String encodePrivateKey(byte[] keyBytes) {
        String base64 = Base64.getEncoder().encodeToString(keyBytes);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    /**
     * Test fixture pairing a persisted RemoteActor with the keypair the test should use
     * to sign inbox requests on its behalf.
     */
    private record SignedRemoteActor(RemoteActor actor, KeyPair keyPair) {
        String keyId() {
            return actor.getActorUri() + "#main-key";
        }
    }

    /**
     * Creates a remote actor backed by a real RSA keypair, persists it with the matching
     * public key PEM, and returns both. Because {@code lastFetchedAt} is set to now, the
     * controller's federation service will use the cached row instead of making an HTTP
     * call to the remote actor URI during signature verification.
     */
    private SignedRemoteActor createSignedRemoteActor(String actorUri, String username,
                                                      String domain, String displayName)
            throws NoSuchAlgorithmException {
        KeyPair keyPair = generateRsaKeyPair();
        String publicKeyPem = encodePublicKey(keyPair.getPublic().getEncoded());
        RemoteActor actor = RemoteActor.builder()
            .actorUri(actorUri)
            .username(username)
            .domain(domain)
            .displayName(displayName)
            .inboxUrl(actorUri + "/inbox")
            .outboxUrl(actorUri + "/outbox")
            .publicKey(publicKeyPem)
            .publicKeyId(actorUri + "#main-key")
            .lastFetchedAt(Instant.now())
            .build();
        return new SignedRemoteActor(remoteActorRepository.save(actor), keyPair);
    }

    /**
     * Posts the given activity payload to {@code /users/{username}/inbox} with a valid
     * HTTP-Signature, exactly as a real federated server would. The Host header is set
     * to {@code localhost} so it matches what {@link HttpSignatureValidator#signRequest}
     * derives from the inbox URL.
     */
    private org.springframework.test.web.servlet.ResultActions performSignedInboxPost(
            String recipientUsername, Map<String, Object> activity, SignedRemoteActor sender)
            throws Exception {
        String body = objectMapper.writeValueAsString(activity);
        String inboxPath = "/users/" + recipientUsername + "/inbox";
        // signRequest derives host from this URL via URI.getHost(); the Host header on the
        // mock request must match.
        String inboxUrl = "http://localhost" + inboxPath;
        String privateKeyPem = encodePrivateKey(sender.keyPair().getPrivate().getEncoded());
        HttpSignatureValidator.SignatureHeaders sigHeaders = signatureValidator.signRequest(
            "POST", inboxUrl, body, privateKeyPem, sender.keyId()
        );
        return mockMvc.perform(post(inboxPath)
                .contentType("application/activity+json")
                .header("Host", sigHeaders.host)
                .header("Date", sigHeaders.date)
                .header("Digest", sigHeaders.digest)
                .header("Signature", sigHeaders.signature)
                .content(body));
    }

    @Test
    @Disabled("Requires mocking external HTTP calls to WebFinger and remote ActivityPub servers")
    @DisplayName("Should follow a remote user via handle format @username@domain")
    void testFollowRemoteUserWithHandle() throws Exception {
        String remoteHandle = "@alice@fitpub.example";

        // Perform follow request
        MvcResult result = mockMvc.perform(post("/api/users/" + remoteHandle + "/follow")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();

        // Verify follow record was created with PENDING status
        String actorUri = baseUrl + "/users/alice"; // Would be resolved via WebFinger in real scenario
        Follow follow = followRepository.findByFollowerIdAndFollowingActorUri(testUser.getId(), actorUri)
            .orElse(null);

        // Note: In a real scenario, this would require mocking WebFinger discovery
        // For now, we verify the endpoint accepts the format
        assertThat(result.getResponse().getContentAsString()).contains("PENDING");
    }

    @Test
    @DisplayName("Should process incoming Follow activity and create follow relationship")
    void testProcessIncomingFollowActivity() throws Exception {
        SignedRemoteActor sender = createSignedRemoteActor(
            "https://remote.example/users/bob", "bob", "remote.example", "Bob Remote"
        );

        // Create Follow activity
        String followId = "https://remote.example/activities/follow/" + UUID.randomUUID();
        Map<String, Object> followActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Follow",
            "id", followId,
            "actor", sender.actor().getActorUri(),
            "object", baseUrl + "/users/" + testUser.getUsername(),
            "published", Instant.now().toString()
        );

        // Post to inbox with a valid HTTP signature
        performSignedInboxPost(testUser.getUsername(), followActivity, sender)
            .andExpect(status().isAccepted());

        // Verify follow relationship was created
        Follow follow = followRepository.findByRemoteActorUriAndFollowingActorUri(
            sender.actor().getActorUri(),
            baseUrl + "/users/" + testUser.getUsername()
        ).orElse(null);

        assertThat(follow).isNotNull();
        assertThat(follow.getStatus()).isEqualTo(Follow.FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Should process Accept activity and update follow status to ACCEPTED")
    void testProcessAcceptActivity() throws Exception {
        SignedRemoteActor sender = createSignedRemoteActor(
            "https://remote.example/users/carol", "carol", "remote.example", "Carol Remote"
        );

        // Create pending follow
        String followActivityId = baseUrl + "/activities/follow/" + UUID.randomUUID();
        Follow pendingFollow = Follow.builder()
            .followerId(testUser.getId())
            .followingActorUri(sender.actor().getActorUri())
            .status(Follow.FollowStatus.PENDING)
            .activityId(followActivityId)
            .build();
        pendingFollow = followRepository.save(pendingFollow);

        // Create Accept activity
        Map<String, Object> acceptActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Accept",
            "id", "https://remote.example/activities/accept/" + UUID.randomUUID(),
            "actor", sender.actor().getActorUri(),
            "object", followActivityId
        );

        // Post Accept to inbox with a valid HTTP signature
        performSignedInboxPost(testUser.getUsername(), acceptActivity, sender)
            .andExpect(status().isAccepted());

        // Verify follow status was updated to ACCEPTED
        Follow updatedFollow = followRepository.findById(pendingFollow.getId()).orElseThrow();
        assertThat(updatedFollow.getStatus()).isEqualTo(Follow.FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Should import its own exported public activity through inbox")
    void testActivityRoundtripThroughExportAndInbox() throws Exception {
        User importingUser = testUser;
        User exportingUser = createFederatedUser("janedoe", "janedoe@example.com", "Jane Doe");

        Activity activity = activityRepository.save(Activity.builder()
            .userId(exportingUser.getId())
            .activityType(Activity.ActivityType.RUN)
            .title("Lunch Run")
            .description("Sunny run in the city")
            .startedAt(LocalDateTime.of(2026, 5, 2, 12, 0))
            .endedAt(LocalDateTime.of(2026, 5, 2, 12, 30))
            .createdAt(LocalDateTime.of(2026, 5, 2, 12, 31, 45, 123_000_000))
            .visibility(Activity.Visibility.PUBLIC)
            .totalDistance(BigDecimal.valueOf(5000))
            .totalDurationSeconds(1800L)
            .elevationGain(BigDecimal.valueOf(100))
            .sourceFileFormat("FIT")
            .published(true)
            .build());

        String exportingActorUri = baseUrl + "/users/" + exportingUser.getUsername();
        when(activityImageService.getActivityImageFile(activity.getId()))
            .thenReturn(new File("/definitely/nonexistent-fitpub-roundtrip-image"));

        remoteActorRepository.save(RemoteActor.builder()
            .actorUri(exportingActorUri)
            .username(exportingUser.getUsername())
            .domain(java.net.URI.create(baseUrl).getHost())
            .displayName(exportingUser.getDisplayName())
            .inboxUrl(exportingActorUri + "/inbox")
            .outboxUrl(exportingActorUri + "/outbox")
            .publicKey(exportingUser.getPublicKey())
            .publicKeyId(exportingActorUri + "#main-key")
            .lastFetchedAt(Instant.now())
            .build());

        followRepository.save(Follow.builder()
            .followerId(importingUser.getId())
            .followingActorUri(exportingActorUri)
            .status(Follow.FollowStatus.ACCEPTED)
            .activityId(baseUrl + "/activities/follow/" + UUID.randomUUID())
            .build());

        MvcResult exportResult = mockMvc.perform(get("/activities/" + activity.getId())
                .accept("application/activity+json"))
            .andExpect(status().isOk())
            .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> exportedNote = objectMapper.readValue(exportResult.getResponse().getContentAsByteArray(), Map.class);

        Map<String, Object> createActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Create",
            "id", baseUrl + "/activities/create/" + UUID.randomUUID(),
            "actor", exportingActorUri,
            "object", exportedNote
        );

        String privateKeyPem = exportingUser.getPrivateKey();
        String inboxPath = "/users/" + importingUser.getUsername() + "/inbox";
        String inboxUrl = "http://localhost" + inboxPath;
        String body = objectMapper.writeValueAsString(createActivity);
        HttpSignatureValidator.SignatureHeaders sigHeaders = signatureValidator.signRequest(
            "POST", inboxUrl, body, privateKeyPem, exportingActorUri + "#main-key"
        );

        mockMvc.perform(post(inboxPath)
                .contentType("application/activity+json")
                .header("Host", sigHeaders.host)
                .header("Date", sigHeaders.date)
                .header("Digest", sigHeaders.digest)
                .header("Signature", sigHeaders.signature)
                .content(body))
            .andExpect(status().isAccepted());

        RemoteActivity imported = remoteActivityRepository.findByActivityUri((String) exportedNote.get("id"))
            .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> workoutData = (Map<String, Object>) exportedNote.get("workoutData");

        assertThat(imported.getActivityUri()).isEqualTo(exportedNote.get("id"));
        assertThat(imported.getRemoteActorUri()).isEqualTo(exportingActorUri);
        assertThat(imported.getTitle()).isEqualTo(exportedNote.getOrDefault("name",
            exportedNote.getOrDefault("summary", "Untitled Activity")));
        assertThat(imported.getDescription()).isEqualTo(workoutData.get("description"));
        assertThat(imported.getPublishedAt()).isEqualTo(Instant.parse((String) exportedNote.get("published")));
        assertThat(imported.getVisibility()).isEqualTo(RemoteActivity.Visibility.PUBLIC);
        assertThat(imported.getActivityType()).isEqualTo(workoutData.get("activityType"));
        assertThat(imported.getTotalDistance()).isEqualTo(5000L);
        assertThat(imported.getTotalDurationSeconds()).isEqualTo(1800L);
        assertThat(imported.getElevationGain()).isEqualTo(workoutData.get("elevationGain"));
        assertThat(imported.getAveragePaceSeconds()).isNull();
        assertThat(imported.getAverageHeartRate()).isNull();
        assertThat(imported.getMaxSpeed()).isNull();
        assertThat(imported.getAverageSpeed()).isNull();
        assertThat(imported.getCalories()).isNull();
        assertThat(imported.getMapImageUrl()).isNull();
        assertThat(imported.getTrackGeojsonUrl()).isNull();
        assertThat(imported.getSimplifiedTrack()).isNull();
    }

    @Test
    @DisplayName("Should reject inbox POST without HTTP signature with 401")
    void testInboxRejectsUnsignedRequest() throws Exception {
        Map<String, Object> followActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Follow",
            "id", "https://remote.example/activities/follow/" + UUID.randomUUID(),
            "actor", "https://remote.example/users/bob",
            "object", baseUrl + "/users/" + testUser.getUsername()
        );

        mockMvc.perform(post("/users/" + testUser.getUsername() + "/inbox")
                .contentType("application/activity+json")
                .content(objectMapper.writeValueAsString(followActivity)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject inbox POST when activity actor host does not match signing key host")
    void testInboxRejectsActorHostMismatch() throws Exception {
        // The signing actor lives on remote.example, but the activity claims to be from
        // someone on impostor.example. The controller must reject this with 401 to
        // prevent one federated server impersonating users on another.
        SignedRemoteActor sender = createSignedRemoteActor(
            "https://remote.example/users/bob", "bob", "remote.example", "Bob Remote"
        );

        Map<String, Object> followActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Follow",
            "id", "https://remote.example/activities/follow/" + UUID.randomUUID(),
            // Forged: claims to be from a user on a completely different host
            "actor", "https://impostor.example/users/eve",
            "object", baseUrl + "/users/" + testUser.getUsername()
        );

        performSignedInboxPost(testUser.getUsername(), followActivity, sender)
            .andExpect(status().isUnauthorized());
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html
            .replaceAll("<br\\s*/?>", "\n")
            .replaceAll("<p>", "")
            .replaceAll("</p>", "\n")
            .replaceAll("<[^>]+>", "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .trim();
    }

    @Test
    @DisplayName("Should process Undo Follow activity and remove follow relationship")
    void testProcessUndoFollowActivity() throws Exception {
        SignedRemoteActor sender = createSignedRemoteActor(
            "https://remote.example/users/dave", "dave", "remote.example", "Dave Remote"
        );

        // Create accepted follow
        Follow acceptedFollow = Follow.builder()
            .remoteActorUri(sender.actor().getActorUri())
            .followingActorUri(baseUrl + "/users/" + testUser.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        acceptedFollow = followRepository.save(acceptedFollow);

        // Create Undo Follow activity
        Map<String, Object> undoActivity = Map.of(
            "@context", "https://www.w3.org/ns/activitystreams",
            "type", "Undo",
            "id", "https://remote.example/activities/undo/" + UUID.randomUUID(),
            "actor", sender.actor().getActorUri(),
            "object", Map.of(
                "type", "Follow",
                "actor", sender.actor().getActorUri(),
                "object", baseUrl + "/users/" + testUser.getUsername()
            )
        );

        // Post Undo to inbox with a valid HTTP signature
        performSignedInboxPost(testUser.getUsername(), undoActivity, sender)
            .andExpect(status().isAccepted());

        // Verify follow was deleted
        boolean followExists = followRepository.existsById(acceptedFollow.getId());
        assertThat(followExists).isFalse();
    }

    @Test
    @DisplayName("Should return followers list including both local and remote followers")
    void testGetFollowersList() throws Exception {
        // Generate keypair for local follower
        KeyPair keyPair = generateRsaKeyPair();

        // Create a local follower
        User localFollower = User.builder()
            .username("localfollower")
            .email("local@example.com")
            .passwordHash(passwordEncoder.encode("password"))
            .displayName("Local Follower")
            .publicKey(encodePublicKey(keyPair.getPublic().getEncoded()))
            .privateKey(encodePrivateKey(keyPair.getPrivate().getEncoded()))
            .enabled(true)
            .build();
        localFollower = userRepository.save(localFollower);

        Follow localFollow = Follow.builder()
            .followerId(localFollower.getId())
            .followingActorUri(baseUrl + "/users/" + testUser.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        followRepository.save(localFollow);

        // Create a remote follower
        RemoteActor remoteFollower = RemoteActor.builder()
            .actorUri("https://remote.example/users/eve")
            .username("eve")
            .domain("remote.example")
            .displayName("Eve Remote")
            .inboxUrl("https://remote.example/users/eve/inbox")
            .outboxUrl("https://remote.example/users/eve/outbox")
            .publicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----")
            .lastFetchedAt(Instant.now())
            .build();
        remoteFollower = remoteActorRepository.save(remoteFollower);

        Follow remoteFollow = Follow.builder()
            .remoteActorUri(remoteFollower.getActorUri())
            .followingActorUri(baseUrl + "/users/" + testUser.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        followRepository.save(remoteFollow);

        // Get followers list
        mockMvc.perform(get("/api/users/" + testUser.getUsername() + "/followers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.username == 'localfollower')]").exists())
            .andExpect(jsonPath("$[?(@.username == 'eve')]").exists())
            .andExpect(jsonPath("$[?(@.local == true)]").exists())
            .andExpect(jsonPath("$[?(@.local == false)]").exists());
    }

    @Test
    @DisplayName("Should return following list including both local and remote users")
    void testGetFollowingList() throws Exception {
        // Generate keypair for local followed user
        KeyPair keyPair = generateRsaKeyPair();

        // Create a local user being followed
        User localFollowed = User.builder()
            .username("localfollowed")
            .email("followed@example.com")
            .passwordHash(passwordEncoder.encode("password"))
            .displayName("Local Followed")
            .publicKey(encodePublicKey(keyPair.getPublic().getEncoded()))
            .privateKey(encodePrivateKey(keyPair.getPrivate().getEncoded()))
            .enabled(true)
            .build();
        localFollowed = userRepository.save(localFollowed);

        Follow localFollow = Follow.builder()
            .followerId(testUser.getId())
            .followingActorUri(baseUrl + "/users/" + localFollowed.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        followRepository.save(localFollow);

        // Create a remote user being followed
        RemoteActor remoteFollowed = RemoteActor.builder()
            .actorUri("https://remote.example/users/frank")
            .username("frank")
            .domain("remote.example")
            .displayName("Frank Remote")
            .inboxUrl("https://remote.example/users/frank/inbox")
            .outboxUrl("https://remote.example/users/frank/outbox")
            .publicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----")
            .lastFetchedAt(Instant.now())
            .build();
        remoteFollowed = remoteActorRepository.save(remoteFollowed);

        Follow remoteFollow = Follow.builder()
            .followerId(testUser.getId())
            .followingActorUri(remoteFollowed.getActorUri())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        followRepository.save(remoteFollow);

        // Get following list
        mockMvc.perform(get("/api/users/" + testUser.getUsername() + "/following"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.username == 'localfollowed')]").exists())
            .andExpect(jsonPath("$[?(@.username == 'frank')]").exists())
            .andExpect(jsonPath("$[?(@.local == true)]").exists())
            .andExpect(jsonPath("$[?(@.local == false)]").exists());
    }

    @Test
    @Disabled("Requires mocking external HTTP calls to WebFinger and remote ActivityPub servers")
    @DisplayName("Should prevent duplicate follow relationships")
    void testPreventDuplicateFollows() throws Exception {
        // Create a remote actor
        RemoteActor remoteActor = RemoteActor.builder()
            .actorUri("https://remote.example/users/grace")
            .username("grace")
            .domain("remote.example")
            .displayName("Grace Remote")
            .inboxUrl("https://remote.example/users/grace/inbox")
            .outboxUrl("https://remote.example/users/grace/outbox")
            .publicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----")
            .lastFetchedAt(Instant.now())
            .build();
        remoteActor = remoteActorRepository.save(remoteActor);

        // Create existing follow
        Follow existingFollow = Follow.builder()
            .followerId(testUser.getId())
            .followingActorUri(remoteActor.getActorUri())
            .status(Follow.FollowStatus.ACCEPTED)
            .build();
        followRepository.save(existingFollow);

        // Try to follow again - should get appropriate response
        String remoteHandle = "@grace@remote.example";

        mockMvc.perform(post("/api/users/" + remoteHandle + "/follow")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is4xxClientError()); // Should return error for duplicate follow
    }
}
