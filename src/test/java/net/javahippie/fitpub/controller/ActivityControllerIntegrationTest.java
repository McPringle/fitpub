package net.javahippie.fitpub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.javahippie.fitpub.model.dto.ActivityDTO;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.security.HttpSignatureValidator;
import net.javahippie.fitpub.security.JwtTokenProvider;
import net.javahippie.fitpub.config.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ActivityController REST API endpoints.
 * Tests the full stack from HTTP request to database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class ActivityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private RemoteActorRepository remoteActorRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private HttpSignatureValidator signatureValidator;

    private User testUser;
    private String authToken;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User();
        testUser.setUsername("testuser_" + System.currentTimeMillis());
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("$2a$10$test.hash.here");
        testUser.setDisplayName("Test User");
        testUser.setEnabled(true);
        testUser.setPublicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----");
        testUser.setPrivateKey("-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----");
        testUser = userRepository.save(testUser);

        // Generate JWT token
        authToken = jwtTokenProvider.createToken(testUser.getUsername());
    }

    private record SignedRemoteActor(RemoteActor actor, KeyPair keyPair) {
        String keyId() {
            return actor.getActorUri() + "#main-key";
        }
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

    private SignedRemoteActor createSignedRemoteActor(String actorUri, String username, String domain, String displayName)
        throws NoSuchAlgorithmException {
        KeyPair keyPair = generateRsaKeyPair();
        RemoteActor actor = RemoteActor.builder()
            .actorUri(actorUri)
            .username(username)
            .domain(domain)
            .displayName(displayName)
            .inboxUrl(actorUri + "/inbox")
            .outboxUrl(actorUri + "/outbox")
            .publicKey(encodePublicKey(keyPair.getPublic().getEncoded()))
            .publicKeyId(actorUri + "#main-key")
            .lastFetchedAt(Instant.now())
            .build();
        return new SignedRemoteActor(remoteActorRepository.save(actor), keyPair);
    }

    private ResultActions performSignedActivityGet(UUID activityId, SignedRemoteActor remoteActor)
        throws Exception {
        String path = "/api/activities/" + activityId;
        String url = "http://localhost" + path;
        String privateKeyPem = encodePrivateKey(remoteActor.keyPair().getPrivate().getEncoded());
        HttpSignatureValidator.SignatureHeaders sigHeaders = signatureValidator.signRequest(
            "GET", url, "", privateKeyPem, remoteActor.keyId()
        );
        return mockMvc.perform(get(path)
            .header("Host", sigHeaders.host)
            .header("Date", sigHeaders.date)
            .header("Digest", sigHeaders.digest)
            .header("Signature", sigHeaders.signature));
    }

    @Test
    @DisplayName("POST /api/activities/upload - Should upload FIT file successfully")
    void testUploadFitFile() throws Exception {
        // Given
        byte[] fitFileData = Files.readAllBytes(Paths.get("src/test/resources/69287079d5e0a4532ba818ee.fit"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-activity.fit",
                "application/octet-stream",
                fitFileData
        );

        // When & Then
        mockMvc.perform(multipart("/api/activities/upload")
                        .file(file)
                        .param("title", "Test Run")
                        .param("description", "Integration test activity")
                        .param("visibility", "PUBLIC")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Run"))
                .andExpect(jsonPath("$.description").value("Integration test activity"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.activityType").exists())
                .andExpect(jsonPath("$.totalDistance").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    @DisplayName("POST /api/activities/upload - Should reject upload without authentication")
    void testUploadFitFile_Unauthorized() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.fit",
                "application/octet-stream",
                new byte[100]
        );

        // When & Then
        mockMvc.perform(multipart("/api/activities/upload")
                        .file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should get activity by ID")
    void testGetActivity() throws Exception {
        // Given
        Activity activity = createTestActivity();
        activity = activityRepository.save(activity);

        // When & Then
        mockMvc.perform(get("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activity.getId().toString()))
                .andExpect(jsonPath("$.title").value(activity.getTitle()))
                .andExpect(jsonPath("$.activityType").value("Run")); // Enum is capitalized
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should allow signed access to PUBLIC activity without follow")
    void testGetActivity_Public_AllowsSignedRemoteAccess() throws Exception {
        Activity activity = createTestActivity();
        activity.setVisibility(Activity.Visibility.PUBLIC);
        activity = activityRepository.save(activity);

        SignedRemoteActor remoteActor = createSignedRemoteActor(
            "https://remote.example/users/alice", "alice", "remote.example", "Alice");

        performSignedActivityGet(activity.getId(), remoteActor)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(activity.getId().toString()))
            .andExpect(jsonPath("$.title").value(activity.getTitle()));
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should allow signed access to FOLLOWERS activity for accepted remote follower")
    void testGetActivity_Followers_AllowsAcceptedSignedRemoteFollower() throws Exception {
        Activity activity = createTestActivity();
        activity.setVisibility(Activity.Visibility.FOLLOWERS);
        activity = activityRepository.save(activity);

        SignedRemoteActor remoteActor = createSignedRemoteActor(
            "https://remote.example/users/alice", "alice", "remote.example", "Alice");
        followRepository.save(Follow.builder()
            .remoteActorUri(remoteActor.actor().getActorUri())
            .followingActorUri("http://localhost/users/" + testUser.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .activityId("https://remote.example/activities/follow/" + UUID.randomUUID())
            .build());

        performSignedActivityGet(activity.getId(), remoteActor)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(activity.getId().toString()));
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should reject signed access to FOLLOWERS activity for non-follower")
    void testGetActivity_Followers_RejectsSignedRemoteNonFollower() throws Exception {
        Activity activity = createTestActivity();
        activity.setVisibility(Activity.Visibility.FOLLOWERS);
        activity = activityRepository.save(activity);

        SignedRemoteActor remoteActor = createSignedRemoteActor(
            "https://remote.example/users/alice", "alice", "remote.example", "Alice");

        performSignedActivityGet(activity.getId(), remoteActor)
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should reject signed access to PRIVATE activity even for accepted remote follower")
    void testGetActivity_Private_RejectsSignedRemoteFollower() throws Exception {
        Activity activity = createTestActivity();
        activity.setVisibility(Activity.Visibility.PRIVATE);
        activity = activityRepository.save(activity);

        SignedRemoteActor remoteActor = createSignedRemoteActor(
            "https://remote.example/users/alice", "alice", "remote.example", "Alice");
        followRepository.save(Follow.builder()
            .remoteActorUri(remoteActor.actor().getActorUri())
            .followingActorUri("http://localhost/users/" + testUser.getUsername())
            .status(Follow.FollowStatus.ACCEPTED)
            .activityId("https://remote.example/activities/follow/" + UUID.randomUUID())
            .build());

        performSignedActivityGet(activity.getId(), remoteActor)
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/activities/{id} - Should return 404 for non-existent activity")
    void testGetActivity_NotFound() throws Exception {
        // Given
        UUID nonExistentId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/activities/" + nonExistentId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/activities - Should list user's activities")
    void testListActivities() throws Exception {
        // Given
        Activity activity1 = createTestActivity();
        activity1.setTitle("Run 1");
        activityRepository.save(activity1);

        Activity activity2 = createTestActivity();
        activity2.setTitle("Run 2");
        activity2.setStartedAt(LocalDateTime.now().minusDays(1));
        activityRepository.save(activity2);

        // When & Then
        mockMvc.perform(get("/api/activities")
                        .header("Authorization", "Bearer " + authToken)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.content[*].title", hasItem("Run 1")))
                .andExpect(jsonPath("$.content[*].title", hasItem("Run 2")));
    }

    @Test
    @DisplayName("PUT /api/activities/{id} - Should update activity metadata")
    void testUpdateActivity() throws Exception {
        // Given
        Activity activity = createTestActivity();
        activity = activityRepository.save(activity);

        ActivityDTO updateDTO = new ActivityDTO();
        updateDTO.setTitle("Updated Title");
        updateDTO.setDescription("Updated Description");
        updateDTO.setVisibility("FOLLOWERS");

        // When & Then
        mockMvc.perform(put("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.description").value("Updated Description"))
                .andExpect(jsonPath("$.visibility").value("FOLLOWERS"));
    }

    @Test
    @DisplayName("PUT /api/activities/{id} - Should reject update of another user's activity")
    void testUpdateActivity_Forbidden() throws Exception {
        // Given
        User anotherUser = new User();
        anotherUser.setUsername("otheruser_" + System.currentTimeMillis());
        anotherUser.setEmail("other@example.com");
        anotherUser.setPasswordHash("$2a$10$test");
        anotherUser.setDisplayName("Other User");
        anotherUser.setEnabled(true);
        anotherUser.setPublicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----");
        anotherUser.setPrivateKey("-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----");
        anotherUser = userRepository.save(anotherUser);

        Activity activity = createTestActivity();
        activity.setUserId(anotherUser.getId());
        activity = activityRepository.save(activity);

        ActivityDTO updateDTO = new ActivityDTO();
        updateDTO.setTitle("Hacked Title");

        // When & Then
        mockMvc.perform(put("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDTO)))
                .andExpect(status().isBadRequest()); // Returns 400 for validation error or not found
    }

    @Test
    @DisplayName("DELETE /api/activities/{id} - Should delete activity")
    void testDeleteActivity() throws Exception {
        // Given
        Activity activity = createTestActivity();
        activity = activityRepository.save(activity);

        // When & Then
        mockMvc.perform(delete("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify deletion
        mockMvc.perform(get("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/activities/{id} - Should reject deletion of another user's activity")
    void testDeleteActivity_Forbidden() throws Exception {
        // Given
        User anotherUser = new User();
        anotherUser.setUsername("deletetest_" + System.currentTimeMillis());
        anotherUser.setEmail("delete@example.com");
        anotherUser.setPasswordHash("$2a$10$test");
        anotherUser.setDisplayName("Delete Test");
        anotherUser.setEnabled(true);
        anotherUser.setPublicKey("-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----");
        anotherUser.setPrivateKey("-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----");
        anotherUser = userRepository.save(anotherUser);

        Activity activity = createTestActivity();
        activity.setUserId(anotherUser.getId());
        activity = activityRepository.save(activity);

        // When & Then
        mockMvc.perform(delete("/api/activities/" + activity.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound()); // Returns 404 because query uses userId filter
    }

    @Test
    @DisplayName("GET /api/activities/user/{username} - Should get public activities of a user")
    void testGetUserPublicActivities() throws Exception {
        // Given
        Activity publicActivity = createTestActivity();
        publicActivity.setTitle("Public Run");
        publicActivity.setVisibility(Activity.Visibility.PUBLIC);
        activityRepository.save(publicActivity);

        Activity privateActivity = createTestActivity();
        privateActivity.setTitle("Private Run");
        privateActivity.setVisibility(Activity.Visibility.PRIVATE);
        activityRepository.save(privateActivity);

        // When & Then
        mockMvc.perform(get("/api/activities/user/" + testUser.getUsername())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", hasItem("Public Run")))
                .andExpect(jsonPath("$.content[*].title", not(hasItem("Private Run"))));
    }

    // Helper method to create test activity
    private Activity createTestActivity() {
        return Activity.builder()
                .userId(testUser.getId())
                .activityType(Activity.ActivityType.RUN)
                .title("Test Activity")
                .description("Test Description")
                .startedAt(LocalDateTime.now().minusHours(1))
                .endedAt(LocalDateTime.now())
                .visibility(Activity.Visibility.PUBLIC)
                .totalDistance(BigDecimal.valueOf(5000))
                .totalDurationSeconds(1800L)
                .elevationGain(BigDecimal.valueOf(100))
                .sourceFileFormat("FIT")
                .build();
    }
}
