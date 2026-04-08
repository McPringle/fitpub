package net.javahippie.fitpub.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.activitypub.Actor;
import net.javahippie.fitpub.model.activitypub.OrderedCollection;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.security.HttpSignatureValidator;
import net.javahippie.fitpub.service.ActivityImageService;
import net.javahippie.fitpub.service.FederationService;
import net.javahippie.fitpub.service.InboxProcessor;
import net.javahippie.fitpub.util.ActivityFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ActivityPub protocol controller.
 * Implements ActivityPub server-to-server (S2S) protocol endpoints.
 *
 * Spec: https://www.w3.org/TR/activitypub/
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ActivityPubController {

    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final ActivityImageService activityImageService;
    private final InboxProcessor inboxProcessor;
    private final FollowRepository followRepository;
    private final HttpSignatureValidator signatureValidator;
    private final FederationService federationService;
    private final ObjectMapper objectMapper;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    private static final String ACTIVITY_JSON = "application/activity+json";
    private static final String LD_JSON = "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"";

    /** Matches the keyId field of an HTTP Signature header. */
    private static final Pattern SIGNATURE_KEY_ID_PATTERN = Pattern.compile("keyId=\"([^\"]+)\"");

    /**
     * Actor profile endpoint.
     * Returns the ActivityPub Actor object for a user.
     *
     * @param username the username
     * @return Actor object in JSON-LD format
     */
    @GetMapping(
        value = "/users/{username}",
        produces = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<Actor> getActor(@PathVariable String username) {
        log.debug("ActivityPub actor request for user: {}", username);

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            log.warn("User not found for ActivityPub request: {}", username);
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        Actor actor = Actor.fromUser(user, baseUrl);

        return ResponseEntity.ok(actor);
    }

    /**
     * Inbox endpoint for receiving ActivityPub activities.
     * POST /users/{username}/inbox
     *
     * <p>Performs full HTTP-Signature validation on every incoming request:
     * <ol>
     *   <li>Reject if {@code Signature} or {@code Digest} headers are missing.</li>
     *   <li>Verify the {@code Digest} header actually matches the body's SHA-256 hash.</li>
     *   <li>Resolve the signing key by fetching the actor referenced in {@code keyId}.</li>
     *   <li>Verify the request signature with the actor's public key.</li>
     *   <li>Bind the activity's {@code actor} field to the signing key's host so a federated
     *       server cannot deliver activities on behalf of users on a different host.</li>
     * </ol>
     * Any failure of steps 1–5 produces a 401. Transient upstream failures (cannot fetch the
     * actor) produce 502 so the sender will retry per ActivityPub spec.
     *
     * @param username the local recipient username
     * @param body the raw request body bytes (preserved for digest verification)
     * @param request the inbound request, used for header collection and request-target
     * @return 202 Accepted on success, 401 Unauthorized on signature failures, 502 on upstream
     *         failures, 400 on malformed JSON
     */
    @PostMapping(
        value = "/users/{username}/inbox",
        consumes = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<Void> inbox(
        @PathVariable String username,
        @RequestBody byte[] body,
        HttpServletRequest request
    ) {
        // 1. Signature header required
        String signatureHeader = request.getHeader("Signature");
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Inbox request for user {} missing Signature header", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 2. Digest header required and must actually match the body.
        // The signature covers the digest header value, but verifying the digest against the
        // real body closes the loop: an attacker who lifted a signed envelope cannot swap the
        // payload underneath it.
        String digestHeader = request.getHeader("Digest");
        if (digestHeader == null || digestHeader.isBlank()) {
            log.warn("Inbox request for user {} missing Digest header", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String expectedDigest = computeSha256Digest(body);
        if (!expectedDigest.equals(digestHeader.trim())) {
            log.warn("Inbox request for user {}: Digest header does not match body hash", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 3. Extract the keyId from the signature header
        String keyId = extractKeyId(signatureHeader);
        if (keyId == null) {
            log.warn("Inbox request for user {}: Signature header has no keyId", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // The keyId is conventionally "https://example.com/users/alice#main-key"; strip
        // the fragment to get the actor URI we should fetch.
        String actorUriFromKey = keyId.contains("#") ? keyId.substring(0, keyId.indexOf('#')) : keyId;
        URI keyHostUri;
        try {
            keyHostUri = new URI(actorUriFromKey);
            if (keyHostUri.getHost() == null) {
                throw new URISyntaxException(actorUriFromKey, "missing host");
            }
        } catch (URISyntaxException e) {
            log.warn("Inbox request for user {}: keyId is not a valid URI: {}", username, keyId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 4. Fetch the actor and obtain their public key
        String publicKeyPem;
        try {
            RemoteActor remoteActor = federationService.fetchRemoteActor(actorUriFromKey);
            publicKeyPem = remoteActor.getPublicKey();
        } catch (Exception e) {
            // Couldn't reach upstream — treat as transient and let them retry
            log.warn("Inbox request for user {}: failed to fetch remote actor {} for signature verification",
                username, actorUriFromKey, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            log.warn("Inbox request for user {}: remote actor {} has no public key",
                username, actorUriFromKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 5. Verify the signature against all the headers it claims to cover
        Map<String, String> headers = collectHeaders(request);
        if (!signatureValidator.validate(signatureHeader, headers, publicKeyPem)) {
            log.warn("Inbox request for user {}: HTTP signature verification failed (signed by {})",
                username, actorUriFromKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 6. Parse the JSON payload (only after the signature is verified, so we don't
        // waste cycles on unauthenticated input)
        Map<String, Object> activity;
        try {
            activity = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Inbox request for user {}: malformed JSON payload", username, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 7. Bind the activity's actor field to the signing key host. Without this check,
        // a federated server signing as one of its own actors could deliver activities
        // claiming to be from any other server's user.
        Object actorField = activity.get("actor");
        String activityActorUri = actorField instanceof String ? (String) actorField : null;
        if (activityActorUri == null || activityActorUri.isBlank()) {
            log.warn("Inbox request for user {}: activity is missing a string actor field", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            URI actorUri = new URI(activityActorUri);
            String activityHost = actorUri.getHost();
            if (activityHost == null
                || !activityHost.equalsIgnoreCase(keyHostUri.getHost())) {
                log.warn("Inbox request for user {}: activity actor host '{}' does not match signing key host '{}'",
                    username, activityHost, keyHostUri.getHost());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        } catch (URISyntaxException e) {
            log.warn("Inbox request for user {}: activity actor URI is invalid: {}",
                username, activityActorUri);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Received signed ActivityPub activity for user {}: {} from {}",
            username, activity.get("type"), activityActorUri);

        // 8. Hand off to the processor. Errors here are logged but do not affect the
        // ack we send back to the federated server (per ActivityPub spec, the inbox
        // returns 202 Accepted once the message is queued for processing).
        try {
            inboxProcessor.processActivity(username, activity);
        } catch (Exception e) {
            log.error("Error processing inbox activity", e);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Computes the SHA-256 digest of the request body in the format used by the HTTP
     * Signatures spec ({@code "SHA-256=" + base64(sha256(body))}).
     */
    private static String computeSha256Digest(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "SHA-256=" + Base64.getEncoder().encodeToString(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE; this branch is unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** Extracts the {@code keyId} value from a Signature header. */
    private static String extractKeyId(String signatureHeader) {
        var matcher = SIGNATURE_KEY_ID_PATTERN.matcher(signatureHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Collects request headers (lowercased) plus the synthetic {@code (request-target)}
     * pseudo-header used by HTTP Signatures.
     */
    private static Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        // Synthetic pseudo-header: "<method-lowercase> <path-with-query>"
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            path = path + "?" + query;
        }
        headers.put("(request-target)",
            request.getMethod().toLowerCase(Locale.ROOT) + " " + path);
        return headers;
    }

    /**
     * Outbox endpoint for user's activities.
     * GET /users/{username}/outbox
     *
     * @param username the username
     * @return OrderedCollection of activities
     */
    @GetMapping(
        value = "/users/{username}/outbox",
        produces = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<OrderedCollection> outbox(@PathVariable String username) {
        log.debug("Outbox request for user: {}", username);

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String outboxUrl = baseUrl + "/users/" + username + "/outbox";

        // Count public activities for this user
        // Mastodon and other ActivityPub servers primarily use the totalItems count
        long activityCount = activityRepository.countByUserIdAndVisibility(
            user.getId(),
            Activity.Visibility.PUBLIC
        );

        OrderedCollection collection = OrderedCollection.builder()
            .context("https://www.w3.org/ns/activitystreams")
            .type("OrderedCollection")
            .id(outboxUrl)
            .totalItems((int) activityCount)
            .build();

        return ResponseEntity.ok(collection);
    }

    /**
     * Followers collection endpoint.
     * GET /users/{username}/followers
     *
     * @param username the username
     * @return OrderedCollection of followers
     */
    @GetMapping(
        value = "/users/{username}/followers",
        produces = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<OrderedCollection> followers(@PathVariable String username) {
        log.debug("Followers request for user: {}", username);

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String followersUrl = baseUrl + "/users/" + username + "/followers";
        String actorUri = user.getActorUri(baseUrl);

        // Get actual follower count from database
        long followerCount = followRepository.countAcceptedFollowersByActorUri(actorUri);

        OrderedCollection collection = OrderedCollection.builder()
            .context("https://www.w3.org/ns/activitystreams")
            .type("OrderedCollection")
            .id(followersUrl)
            .totalItems((int) followerCount)
            .build();

        return ResponseEntity.ok(collection);
    }

    /**
     * Following collection endpoint.
     * GET /users/{username}/following
     *
     * @param username the username
     * @return OrderedCollection of following
     */
    @GetMapping(
        value = "/users/{username}/following",
        produces = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<OrderedCollection> following(@PathVariable String username) {
        log.debug("Following request for user: {}", username);

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String followingUrl = baseUrl + "/users/" + username + "/following";

        // Get actual following count from database
        long followingCount = followRepository.countAcceptedFollowingByUserId(user.getId());

        OrderedCollection collection = OrderedCollection.builder()
            .context("https://www.w3.org/ns/activitystreams")
            .type("OrderedCollection")
            .id(followingUrl)
            .totalItems((int) followingCount)
            .build();

        return ResponseEntity.ok(collection);
    }

    /**
     * Activity object endpoint.
     * Returns a single activity as an ActivityPub Note object.
     * This is needed for quote posts and other federation features.
     *
     * GET /activities/{id}
     *
     * @param id the activity ID
     * @return Note object in ActivityPub format
     */
    @GetMapping(
        value = "/activities/{id}",
        produces = {ACTIVITY_JSON, LD_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    public ResponseEntity<Map<String, Object>> getActivity(@PathVariable UUID id) {
        log.debug("ActivityPub activity request for ID: {}", id);

        Optional<Activity> activityOpt = activityRepository.findById(id);
        if (activityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Activity activity = activityOpt.get();

        // Only return public activities via ActivityPub
        if (activity.getVisibility() != Activity.Visibility.PUBLIC) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Get the user
        Optional<User> userOpt = userRepository.findById(activity.getUserId());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        String actorUri = baseUrl + "/users/" + user.getUsername();
        String activityUri = baseUrl + "/activities/" + activity.getId();

        // Build the Note object (same format as used in federation)
        Map<String, Object> noteObject = new HashMap<>();
        noteObject.put("@context", "https://www.w3.org/ns/activitystreams");
        noteObject.put("id", activityUri);
        noteObject.put("type", "Note");
        noteObject.put("attributedTo", actorUri);
        noteObject.put("published", activity.getCreatedAt().toString());
        noteObject.put("content", formatActivityContent(activity));
        noteObject.put("url", activityUri);

        // Audience
        noteObject.put("to", List.of("https://www.w3.org/ns/activitystreams#Public"));
        noteObject.put("cc", List.of(actorUri + "/followers"));

        // Extract hashtags from user text and add as tags
        List<String> hashtags = extractHashtags(activity);
        if (!hashtags.isEmpty()) {
            List<Map<String, String>> tags = hashtags.stream()
                .map(ht -> {
                    Map<String, String> tag = new HashMap<>();
                    tag.put("type", "Hashtag");
                    tag.put("href", baseUrl + "/tags/" + ht.toLowerCase());
                    tag.put("name", "#" + ht);
                    return tag;
                })
                .toList();
            noteObject.put("tag", tags);
        }

        // Add conversation/context for threading
        noteObject.put("conversation", activityUri);

        // Add activity image if available
        // Check if image exists, otherwise generate it
        File imageFile = activityImageService.getActivityImageFile(activity.getId());
        if (!imageFile.exists()) {
            // Generate image if it doesn't exist
            activityImageService.generateActivityImage(activity);
        }

        // Only add attachment if image was successfully generated/exists
        if (imageFile.exists()) {
            String imageUrl = baseUrl + "/api/activities/" + activity.getId() + "/image";
            Map<String, Object> imageAttachment = new HashMap<>();
            imageAttachment.put("type", "Image");
            imageAttachment.put("mediaType", "image/png");
            imageAttachment.put("url", imageUrl);
            // The "name" field on an Image attachment is what Mastodon, other
            // ActivityPub servers, and screen readers expose as the image
            // description. Build a real prose description from the activity
            // data instead of the previous "Activity map showing X route"
            // placeholder. See ActivityImageService.buildImageAltText.
            imageAttachment.put("name", activityImageService.buildImageAltText(activity));
            noteObject.put("attachment", List.of(imageAttachment));
        }

        return ResponseEntity.ok(noteObject);
    }

    /**
     * Format activity content as HTML for ActivityPub.
     * Mastodon and most Fediverse software expect HTML in the content field.
     */
    private String formatActivityContent(Activity activity) {
        StringBuilder content = new StringBuilder();

        if (activity.getTitle() != null && !activity.getTitle().isEmpty()) {
            content.append("<p><strong>").append(linkifyHashtags(escapeHtml(activity.getTitle()))).append("</strong></p>");
        }

        if (activity.getDescription() != null && !activity.getDescription().isEmpty()) {
            content.append("<p>").append(linkifyHashtags(escapeHtml(activity.getDescription()))).append("</p>");
        }

        String activityEmoji = getActivityEmoji(activity.getActivityType());
        String formattedType = ActivityFormatter.formatActivityType(activity.getActivityType());
        content.append("<p>").append(activityEmoji).append(" ").append(escapeHtml(formattedType)).append("</p>");

        StringBuilder metrics = new StringBuilder();
        if (activity.getTotalDistance() != null) {
            metrics.append("📏 ")
                .append(String.format("%.2f km", activity.getTotalDistance().doubleValue() / 1000.0))
                .append("<br>");
        }
        if (activity.getTotalDurationSeconds() != null) {
            long hours = activity.getTotalDurationSeconds() / 3600;
            long minutes = (activity.getTotalDurationSeconds() % 3600) / 60;
            long seconds = activity.getTotalDurationSeconds() % 60;
            metrics.append("⏱️ ");
            if (hours > 0) {
                metrics.append(hours).append("h ");
            }
            metrics.append(minutes).append("m ").append(seconds).append("s").append("<br>");
        }
        if (activity.getElevationGain() != null) {
            metrics.append("⛰️ ")
                .append(String.format("%.0f m", activity.getElevationGain().doubleValue()))
                .append("<br>");
        }
        if (metrics.length() > 0) {
            content.append("<p>").append(metrics).append("</p>");
        }

        return content.toString();
    }

    private static final java.util.regex.Pattern HASHTAG_PATTERN =
        java.util.regex.Pattern.compile("(?<=^|\\s)#(\\w+)", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    private List<String> extractHashtags(Activity activity) {
        List<String> hashtags = new java.util.ArrayList<>();
        for (String text : List.of(
                activity.getTitle() != null ? activity.getTitle() : "",
                activity.getDescription() != null ? activity.getDescription() : "")) {
            var matcher = HASHTAG_PATTERN.matcher(text);
            while (matcher.find()) {
                String tag = matcher.group(1);
                if (hashtags.stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                    hashtags.add(tag);
                }
            }
        }
        return hashtags;
    }

    private String linkifyHashtags(String escapedHtml) {
        return HASHTAG_PATTERN.matcher(escapedHtml).replaceAll(match -> {
            String tag = match.group(1);
            return "<a href=\"" + baseUrl + "/tags/" + tag.toLowerCase()
                + "\" class=\"mention hashtag\" rel=\"tag\">#<span>" + tag + "</span></a>";
        });
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private String getActivityEmoji(Activity.ActivityType activityType) {
        return switch (activityType) {
            case RUN -> "🏃";
            case RIDE -> "🚴";
            case HIKE -> "🥾";
            case WALK -> "🚶";
            case SWIM -> "🏊";
            default -> "💪";
        };
    }
}
