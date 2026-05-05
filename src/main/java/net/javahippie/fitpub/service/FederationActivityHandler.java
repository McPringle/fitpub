package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.RemoteActivity;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.Comment;
import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.Like;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.RemoteActivityRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.CommentRepository;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.LikeRepository;
import net.javahippie.fitpub.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the domain processing of a single inbound federated activity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederationActivityHandler {
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final FederationService federationService;
    private final ActivityRepository activityRepository;
    private final LikeRepository likeRepository;
    private final CommentRepository commentRepository;
    private final NotificationService notificationService;
    private final RemoteActivityRepository remoteActivityRepository;
    private final RemoteActorRepository remoteActorRepository;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    /**
     * Process an incoming activity.
     *
     * @param username the local username
     * @param activity the activity to process
     */
    @Transactional
    public void processActivity(String username, Map<String, Object> activity) {
        processActivity(username, activity, null);
    }

    @Transactional
    public void processActivity(String username, Map<String, Object> activity, RemoteActivityEnrichment enrichment) {
        String type = (String) activity.get("type");
        log.info("Processing {} activity for user {}", type, username);

        switch (type) {
            case "Follow":
                processFollow(username, activity);
                break;
            case "Undo":
                processUndo(username, activity);
                break;
            case "Accept":
                processAccept(username, activity);
                break;
            case "Create":
                processCreate(username, activity, enrichment);
                break;
            case "Like":
                processLike(username, activity);
                break;
            case "Delete":
                processDelete(username, activity);
                break;
            default:
                log.warn("Unhandled activity type: {}", type);
        }
    }

    /**
     * Process a Follow activity.
     * Remote user wants to follow local user.
     */
    private void processFollow(String username, Map<String, Object> activity) {
        try {
            String activityId = (String) activity.get("id");
            String actor = (String) activity.get("actor");
            String object = (String) activity.get("object");

            User localUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

            String expectedObjectUri = baseUrl + "/users/" + username;
            if (!object.equals(expectedObjectUri)) {
                log.warn("Follow object mismatch. Expected: {}, Got: {}", expectedObjectUri, object);
                return;
            }

            RemoteActor remoteActor = federationService.fetchRemoteActor(actor);

            Follow existing = followRepository.findByActivityId(activityId).orElse(null);
            if (existing != null) {
                log.debug("Follow already processed: {}", activityId);
                return;
            }

            Follow follow = Follow.builder()
                .followerId(null)
                .remoteActorUri(actor)
                .followingActorUri(expectedObjectUri)
                .status(Follow.FollowStatus.ACCEPTED)
                .activityId(activityId)
                .build();

            followRepository.save(follow);
            federationService.sendAcceptActivity(follow, localUser);
            notificationService.createUserFollowedNotification(localUser, actor);

            log.info("Processed Follow from {} for user {}", actor, username);

        } catch (Exception e) {
            log.error("Error processing Follow activity", e);
        }
    }

    private void processUndo(String username, Map<String, Object> activity) {
        try {
            String actor = (String) activity.get("actor");
            Object object = activity.get("object");
            if (object instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> undoObject = (Map<String, Object>) object;
                String type = (String) undoObject.get("type");

                if ("Follow".equals(type)) {
                    String activityId = (String) undoObject.get("id");
                    Follow follow = followRepository.findByActivityId(activityId).orElse(null);
                    if (follow != null) {
                        followRepository.delete(follow);
                        log.info("Processed Undo Follow: {}", activityId);
                    }
                } else if ("Like".equals(type)) {
                    String objectUri = (String) undoObject.get("object");
                    UUID activityId = extractActivityIdFromUri(objectUri);
                    if (activityId != null) {
                        likeRepository.deleteByActivityIdAndRemoteActorUri(activityId, actor);
                        log.info("Processed Undo Like from {} for activity {}", actor, activityId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing Undo activity", e);
        }
    }

    private void processAccept(String username, Map<String, Object> activity) {
        try {
            Object object = activity.get("object");
            String activityId = null;

            if (object instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> acceptObject = (Map<String, Object>) object;
                activityId = (String) acceptObject.get("id");
            } else if (object instanceof String) {
                activityId = (String) object;
            }

            if (activityId != null) {
                Follow follow = followRepository.findByActivityId(activityId).orElse(null);
                if (follow != null && follow.getStatus() == Follow.FollowStatus.PENDING) {
                    follow.setStatus(Follow.FollowStatus.ACCEPTED);
                    followRepository.save(follow);
                    log.info("Follow request accepted: {}", activityId);

                    UUID followerId = follow.getFollowerId();
                    if (followerId != null) {
                        User follower = userRepository.findById(followerId).orElse(null);
                        if (follower != null) {
                            String remoteActorUri = follow.getFollowingActorUri();
                            notificationService.createFollowAcceptedNotification(
                                follower.getId(),
                                remoteActorUri,
                                activityId
                            );
                            log.info("Created follow accepted notification for user {}", follower.getUsername());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing Accept activity", e);
        }
    }

    private void processCreate(String username, Map<String, Object> activity, RemoteActivityEnrichment enrichment) {
        try {
            String actor = (String) activity.get("actor");
            Object object = activity.get("object");

            if (!(object instanceof Map)) {
                log.warn("Create activity object is not a Map");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> noteObject = (Map<String, Object>) object;
            String type = (String) noteObject.get("type");

            if (!"Note".equals(type)) {
                log.debug("Received Create activity with non-Note object type: {}", type);
                return;
            }

            String quoteUri = firstNonNull(
                (String) noteObject.get("quoteUri"),
                (String) noteObject.get("quote"),
                (String) noteObject.get("quoteUrl"),
                (String) noteObject.get("_misskey_quote")
            );

            if (quoteUri != null) {
                handleQuoteApproval(username, activity, actor, quoteUri);
            }

            String inReplyTo = (String) noteObject.get("inReplyTo");

            if (inReplyTo == null) {
                processRemoteActivity(username, actor, noteObject, enrichment);
            } else {
                processComment(username, actor, noteObject, inReplyTo);
            }
        } catch (Exception e) {
            log.error("Error processing Create activity", e);
        }
    }

    private void handleQuoteApproval(String username, Map<String, Object> createActivity, String actor, String quoteUri) {
        try {
            UUID activityId = extractActivityIdFromUri(quoteUri);
            if (activityId == null) {
                log.debug("Quote URI {} does not reference a local activity, skipping approval", quoteUri);
                return;
            }

            Activity localActivity = activityRepository.findById(activityId).orElse(null);
            if (localActivity == null) {
                log.warn("Quoted activity not found: {}", activityId);
                return;
            }

            User localUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

            @SuppressWarnings("unchecked")
            Map<String, Object> noteObject = (Map<String, Object>) createActivity.get("object");
            String noteUri = (String) noteObject.get("id");
            log.info("Approving quote from {} for activity {} (Note URI: {})", actor, activityId, noteUri);

            federationService.sendAcceptQuote(noteUri, actor, localUser);

        } catch (Exception e) {
            log.error("Error handling quote approval for {}", quoteUri, e);
        }
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private void processComment(String username, String actor, Map<String, Object> noteObject, String inReplyTo) {
        try {
            UUID activityId = extractActivityIdFromUri(inReplyTo);
            if (activityId == null) {
                log.warn("Could not extract activity ID from inReplyTo: {}", inReplyTo);
                return;
            }

            Activity localActivity = activityRepository.findById(activityId).orElse(null);
            if (localActivity == null) {
                log.warn("Activity not found: {}", activityId);
                return;
            }

            RemoteActor remoteActor = federationService.fetchRemoteActor(actor);

            String content = (String) noteObject.get("content");
            if (content == null || content.trim().isEmpty()) {
                log.warn("Create/Note has no content");
                return;
            }

            String commentId = (String) noteObject.get("id");
            if (commentRepository.findByActivityPubId(commentId).isPresent()) {
                log.debug("Comment already exists with activityPubId: {}", commentId);
                return;
            }

            Comment comment = Comment.builder()
                .activityId(activityId)
                .userId(null)
                .remoteActorUri(actor)
                .displayName(remoteActor.getDisplayName() != null ? remoteActor.getDisplayName() : remoteActor.getUsername())
                .avatarUrl(remoteActor.getAvatarUrl())
                .content(stripHtml(content))
                .activityPubId(commentId)
                .build();

            commentRepository.save(comment);
            log.info("Processed Create/Note (comment) from {} for activity {}", actor, activityId);

            notificationService.createActivityCommentedNotification(localActivity, comment, actor);

        } catch (Exception e) {
            log.error("Error processing comment", e);
        }
    }

    private void processRemoteActivity(String username, String actor, Map<String, Object> noteObject,
                                       RemoteActivityEnrichment enrichment) {
        try {
            String activityUri = (String) noteObject.get("id");
            if (activityUri == null) {
                log.warn("Remote activity has no id");
                return;
            }

            if (remoteActivityRepository.existsByActivityUri(activityUri)) {
                log.debug("Remote activity already exists: {}", activityUri);
                return;
            }

            RemoteActor remoteActor = federationService.fetchRemoteActor(actor);

            User localUser = userRepository.findByUsername(username).orElse(null);
            if (localUser == null) {
                log.warn("Local user not found: {}", username);
                return;
            }

            boolean isFollowing = followRepository.findByFollowerIdAndFollowingActorUri(
                localUser.getId(), actor
            ).map(follow -> follow.getStatus() == Follow.FollowStatus.ACCEPTED).orElse(false);

            if (!isFollowing) {
                log.debug("Local user {} is not following {}, ignoring activity", username, actor);
                return;
            }

            Map<String, String> attachments = extractAttachments(noteObject);
            RemoteActivity.Visibility visibility = determineVisibility(noteObject);

            String publishedStr = (String) noteObject.get("published");
            Instant publishedAt = parsePublishedAt(publishedStr);

            RemoteActivity remoteActivity = RemoteActivity.builder()
                .activityUri(activityUri)
                .remoteActorUri(actor)
                .activityType(enrichment != null && enrichment.activityType() != null
                    ? enrichment.activityType()
                    : guessActivityType(stringValue(noteObject.getOrDefault("summary", noteObject.get("content")))))
                .title(firstNonBlank(
                    enrichment != null ? enrichment.title() : null,
                    (String) noteObject.get("name"),
                    (String) noteObject.get("summary"),
                    "Untitled Activity"
                ))
                .description(firstNonBlank(
                    enrichment != null ? enrichment.description() : null,
                    stripHtml((String) noteObject.get("content"))
                ))
                .publishedAt(publishedAt)
                .totalDistance(enrichment != null ? enrichment.totalDistance() : null)
                .totalDurationSeconds(enrichment != null ? enrichment.totalDurationSeconds() : null)
                .elevationGain(enrichment != null ? enrichment.elevationGain() : null)
                .averagePaceSeconds(enrichment != null ? enrichment.averagePaceSeconds() : null)
                .averageHeartRate(enrichment != null ? enrichment.averageHeartRate() : null)
                .maxSpeed(enrichment != null ? enrichment.maxSpeed() : null)
                .averageSpeed(enrichment != null ? enrichment.averageSpeed() : null)
                .calories(enrichment != null ? enrichment.calories() : null)
                .mapImageUrl(attachments.get("mapImage"))
                .trackGeojsonUrl(attachments.get("trackGeojson"))
                .simplifiedTrack(enrichment != null ? enrichment.simplifiedTrack() : null)
                .visibility(visibility)
                .activityPubObject(serializeToJson(noteObject))
                .build();

            remoteActivityRepository.save(remoteActivity);
            log.info("Stored remote activity from {}: {} ({})", remoteActor.getUsername(), remoteActivity.getTitle(), activityUri);

        } catch (Exception e) {
            log.error("Error processing remote activity", e);
        }
    }

    private void processLike(String username, Map<String, Object> activity) {
        try {
            String actor = (String) activity.get("actor");
            String objectUri = (String) activity.get("object");
            String content = (String) activity.get("content");
            String emoji = net.javahippie.fitpub.model.ReactionEmoji.normalise(content);

            log.debug("Received Like ({}) from {} for object {}", emoji, actor, objectUri);

            UUID activityId = extractActivityIdFromUri(objectUri);
            if (activityId == null) {
                log.warn("Could not extract activity ID from object URI: {}", objectUri);
                return;
            }

            Activity localActivity = activityRepository.findById(activityId).orElse(null);
            if (localActivity == null) {
                log.warn("Activity not found: {}", activityId);
                return;
            }

            RemoteActor remoteActor = federationService.fetchRemoteActor(actor);

            java.util.Optional<Like> existing =
                likeRepository.findByActivityIdAndRemoteActorUri(activityId, actor);
            if (existing.isPresent()) {
                Like like = existing.get();
                if (!emoji.equals(like.getEmoji())) {
                    like.setEmoji(emoji);
                    like.setDisplayName(remoteActor.getDisplayName() != null
                        ? remoteActor.getDisplayName() : remoteActor.getUsername());
                    like.setAvatarUrl(remoteActor.getAvatarUrl());
                    likeRepository.save(like);
                    log.info("Switched remote reaction from {} on activity {} to {}",
                        actor, activityId, emoji);
                } else {
                    log.debug("Like ({}) already recorded from {} for activity {}",
                        emoji, actor, activityId);
                }
                return;
            }

            Like like = Like.builder()
                .activityId(activityId)
                .userId(null)
                .remoteActorUri(actor)
                .emoji(emoji)
                .displayName(remoteActor.getDisplayName() != null ? remoteActor.getDisplayName() : remoteActor.getUsername())
                .avatarUrl(remoteActor.getAvatarUrl())
                .build();

            likeRepository.save(like);
            log.info("Processed Like ({}) from {} for activity {}", emoji, actor, activityId);

            notificationService.createActivityLikedNotification(localActivity, actor, emoji);

        } catch (Exception e) {
            log.error("Error processing Like activity", e);
        }
    }

    private void processDelete(String username, Map<String, Object> activity) {
        try {
            String actor = (String) activity.get("actor");
            Object object = activity.get("object");

            String objectUri;
            if (object instanceof Map) {
                objectUri = (String) ((Map<?, ?>) object).get("id");
            } else {
                objectUri = (String) object;
            }

            if (objectUri == null) {
                log.warn("Delete activity has no object URI");
                return;
            }

            log.info("Processing Delete from {} for object {}", actor, objectUri);

            if (objectUri.equals(actor)) {
                processActorDelete(actor);
            } else {
                processObjectDelete(objectUri);
            }

        } catch (Exception e) {
            log.error("Error processing Delete activity", e);
        }
    }

    private void processActorDelete(String actorUri) {
        try {
            log.info("Processing actor deletion: {}", actorUri);

            followRepository.deleteByRemoteActorUri(actorUri);
            log.debug("Deleted follows where actor {} was the follower", actorUri);

            followRepository.deleteByFollowingActorUri(actorUri);
            log.debug("Deleted follows where actor {} was being followed", actorUri);

            likeRepository.deleteByRemoteActorUri(actorUri);
            log.debug("Deleted likes from actor {}", actorUri);

            java.util.List<Comment> comments = commentRepository.findByRemoteActorUri(actorUri);
            for (Comment comment : comments) {
                comment.setDeleted(true);
                comment.setContent("[deleted]");
            }
            if (!comments.isEmpty()) {
                commentRepository.saveAll(comments);
                log.debug("Soft-deleted {} comments from actor {}", comments.size(), actorUri);
            }

            remoteActivityRepository.deleteByRemoteActorUri(actorUri);
            log.debug("Deleted remote activities from actor {}", actorUri);

            remoteActorRepository.findByActorUri(actorUri).ifPresent(remoteActor -> {
                remoteActorRepository.delete(remoteActor);
                log.debug("Deleted remote actor record for {}", actorUri);
            });

            log.info("Completed actor deletion for: {}", actorUri);

        } catch (Exception e) {
            log.error("Error processing actor deletion for: {}", actorUri, e);
        }
    }

    private void processObjectDelete(String objectUri) {
        try {
            log.info("Processing object deletion: {}", objectUri);

            remoteActivityRepository.findByActivityUri(objectUri).ifPresent(remoteActivity -> {
                remoteActivityRepository.delete(remoteActivity);
                log.info("Deleted remote activity: {}", objectUri);
            });

            commentRepository.findByActivityPubId(objectUri).ifPresent(comment -> {
                comment.setDeleted(true);
                comment.setContent("[deleted]");
                commentRepository.save(comment);
                log.info("Soft-deleted comment: {}", objectUri);
            });

        } catch (Exception e) {
            log.error("Error processing object deletion for: {}", objectUri, e);
        }
    }

    private UUID extractActivityIdFromUri(String uri) {
        try {
            if (uri == null || !uri.startsWith(baseUrl + "/activities/")) {
                return null;
            }
            String uuidStr = uri.substring((baseUrl + "/activities/").length());
            return UUID.fromString(uuidStr);
        } catch (Exception e) {
            log.warn("Failed to extract activity ID from URI: {}", uri, e);
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String text = html
            .replaceAll("<br\\s*/?>", "\n")
            .replaceAll("<p>", "")
            .replaceAll("</p>", "\n")
            .replaceAll("<[^>]+>", "");

        text = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&");

        return text.trim();
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, String> extractAttachments(Map<String, Object> noteObject) {
        Map<String, String> attachments = new java.util.HashMap<>();

        Object attachmentObj = noteObject.get("attachment");
        if (attachmentObj instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> attachmentList = (java.util.List<Object>) attachmentObj;

            for (Object item : attachmentList) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attach = (Map<String, Object>) item;

                    String type = (String) attach.get("type");
                    String mediaType = (String) attach.get("mediaType");
                    String url = (String) attach.get("url");
                    String name = (String) attach.get("name");

                    if (url != null) {
                        if ("Image".equals(type) && (mediaType != null && mediaType.startsWith("image/"))) {
                            if (name != null && name.toLowerCase().contains("map")) {
                                attachments.put("mapImage", url);
                            }
                        } else if ("Document".equals(type) && "application/geo+json".equals(mediaType)) {
                            attachments.put("trackGeojson", url);
                        }
                    }
                }
            }
        }

        return attachments;
    }

    private RemoteActivity.Visibility determineVisibility(Map<String, Object> noteObject) {
        Object toObj = noteObject.get("to");
        Object ccObj = noteObject.get("cc");

        java.util.List<String> toList = objectToStringList(toObj);
        java.util.List<String> ccList = objectToStringList(ccObj);

        boolean isPublic = toList.contains("https://www.w3.org/ns/activitystreams#Public") ||
            ccList.contains("https://www.w3.org/ns/activitystreams#Public") ||
            toList.contains("as:Public") ||
            ccList.contains("as:Public") ||
            toList.contains("Public") ||
            ccList.contains("Public");

        if (isPublic) {
            return RemoteActivity.Visibility.PUBLIC;
        }

        boolean hasFollowers = toList.stream().anyMatch(s -> s.contains("/followers")) ||
            ccList.stream().anyMatch(s -> s.contains("/followers"));

        if (hasFollowers) {
            return RemoteActivity.Visibility.FOLLOWERS;
        }

        return RemoteActivity.Visibility.PRIVATE;
    }

    private Instant parsePublishedAt(String publishedStr) {
        if (publishedStr == null || publishedStr.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.parse(publishedStr);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return OffsetDateTime.parse(publishedStr).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return ZonedDateTime.parse(publishedStr).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(publishedStr).atOffset(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse published timestamp '{}', falling back to now()", publishedStr);
            return Instant.now();
        }
    }

    private String serializeToJson(Object object) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(object);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return null;
        }
    }

    private java.util.List<String> objectToStringList(Object obj) {
        if (obj == null) {
            return java.util.Collections.emptyList();
        }
        if (obj instanceof String) {
            return java.util.Collections.singletonList((String) obj);
        }
        if (obj instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) obj;
            return list.stream()
                .filter(item -> item instanceof String)
                .map(item -> (String) item)
                .collect(java.util.stream.Collectors.toList());
        }
        return java.util.Collections.emptyList();
    }

    private String guessActivityType(String text) {
        if (text == null) {
            return "UNKNOWN";
        }
        String lower = text.toLowerCase();
        if (lower.contains("run") || lower.contains("jog")) return "RUN";
        if (lower.contains("ride") || lower.contains("bike") || lower.contains("cycl")) return "RIDE";
        if (lower.contains("hike") || lower.contains("walk")) return "HIKE";
        if (lower.contains("swim")) return "SWIM";
        return "UNKNOWN";
    }
}
