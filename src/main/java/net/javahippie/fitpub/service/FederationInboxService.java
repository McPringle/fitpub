package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.FederationInbox;
import net.javahippie.fitpub.repository.FederationInboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists inbound ActivityPub deliveries into the durable federation inbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederationInboxService {

    private final FederationInboxRepository federationInboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public FederationInbox enqueue(String recipientUsername, Map<String, Object> activity) {
        FederationInbox entry = FederationInbox.builder()
            .recipientUsername(recipientUsername)
            .activityType(stringValue(activity.get("type")))
            .actorUri(stringValue(activity.get("actor")))
            .objectUri(extractObjectUri(activity.get("object")))
            .payloadJson(serialize(activity))
            .status(FederationInbox.Status.PENDING)
            .attemptCount(0)
            .nextAttemptAt(LocalDateTime.now())
            .build();

        return federationInboxRepository.save(entry);
    }

    @Transactional
    public Optional<FederationInbox> claimById(UUID id) {
        LocalDateTime now = LocalDateTime.now();
        return federationInboxRepository.findByIdForUpdate(id)
            .filter(entry -> entry.getStatus() == FederationInbox.Status.PENDING)
            .filter(entry -> !entry.getNextAttemptAt().isAfter(now))
            .map(entry -> {
                entry.setStatus(FederationInbox.Status.PROCESSING);
                entry.setProcessingStartedAt(now);
                entry.setAttemptCount(entry.getAttemptCount() + 1);
                entry.setLastError(null);
                entry.setProcessedAt(null);
                return federationInboxRepository.save(entry);
            });
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueEntryIds(int limit) {
        return federationInboxRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscReceivedAtAsc(
                FederationInbox.Status.PENDING,
                LocalDateTime.now(),
                PageRequest.of(0, limit))
            .stream()
            .map(FederationInbox::getId)
            .toList();
    }

    @Transactional
    public void markDone(UUID id) {
        federationInboxRepository.findByIdForUpdate(id).ifPresent(entry -> {
            entry.setStatus(FederationInbox.Status.DONE);
            entry.setProcessedAt(LocalDateTime.now());
            entry.setLastError(null);
            federationInboxRepository.save(entry);
        });
    }

    @Transactional
    public void markForRetry(UUID id, Exception error, int maxAttempts, LocalDateTime nextAttemptAt) {
        federationInboxRepository.findByIdForUpdate(id).ifPresent(entry -> {
            entry.setLastError(truncateError(error));
            entry.setProcessingStartedAt(null);
            entry.setProcessedAt(null);
            if (entry.getAttemptCount() >= maxAttempts) {
                entry.setStatus(FederationInbox.Status.ERROR);
            } else {
                entry.setStatus(FederationInbox.Status.PENDING);
                entry.setNextAttemptAt(nextAttemptAt);
            }
            federationInboxRepository.save(entry);
        });
    }

    private String serialize(Map<String, Object> activity) {
        try {
            return objectMapper.writeValueAsString(activity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize federation inbox payload", e);
        }
    }

    private String extractObjectUri(Object object) {
        if (object instanceof String objectUri) {
            return objectUri;
        }
        if (object instanceof Map<?, ?> objectMap) {
            Object id = objectMap.get("id");
            return stringValue(id);
        }
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String truncateError(Exception error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        if (message.length() <= 4000) {
            return message;
        }
        return message.substring(0, 4000);
    }
}
