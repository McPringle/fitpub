package net.javahippie.fitpub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.FederationInbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Processes persisted federation inbox entries with retry support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederationInboxProcessor {

    private static final long MAX_BACKOFF_SECONDS = 86_400;

    private final FederationInboxService federationInboxService;
    private final FederationActivityHandler federationActivityHandler;
    private final RemoteActivityDetailsFetcher remoteActivityDetailsFetcher;
    private final ObjectMapper objectMapper;

    @Value("${fitpub.activitypub.inbox.max-attempts:10}")
    private int maxAttempts;

    @Value("${fitpub.activitypub.inbox.batch-size:20}")
    private int batchSize;

    @Value("${fitpub.activitypub.inbox.retry-delay-seconds:300}")
    private long retryDelaySeconds;

    @Value("${fitpub.activitypub.inbox.processing-timeout-seconds:900}")
    private long processingTimeoutSeconds;

    @Async("taskExecutor")
    public void triggerAsync(UUID inboxEntryId) {
        trigger(inboxEntryId);
    }

    public void trigger(UUID inboxEntryId) {
        federationInboxService.claimById(inboxEntryId)
            .ifPresent(this::processClaimedEntry);
    }

    @Scheduled(fixedDelayString = "${fitpub.activitypub.inbox.processing-interval-ms:300000}")
    public void processDueEntries() {
        recoverStaleProcessingEntries();
        List<UUID> dueEntryIds = federationInboxService.findDueEntryIds(batchSize);
        for (UUID id : dueEntryIds) {
            trigger(id);
        }
    }

    private void processClaimedEntry(FederationInbox entry) {
        try {
            Map<String, Object> activity = objectMapper.readValue(
                entry.getPayloadJson(),
                new TypeReference<Map<String, Object>>() {}
            );
            RemoteActivityEnrichment enrichment = resolveEnrichment(activity, entry.getRecipientUsername()).orElse(null);
            federationActivityHandler.processActivity(entry.getRecipientUsername(), activity, enrichment);
            federationInboxService.markDone(entry.getId());
        } catch (Exception e) {
            log.warn("Failed processing federation inbox entry {} on attempt {}",
                entry.getId(), entry.getAttemptCount(), e);
            federationInboxService.markForRetry(
                entry.getId(),
                e,
                maxAttempts,
                nextAttemptAt(entry.getAttemptCount())
            );
        }
    }

    private LocalDateTime nextAttemptAt(int attemptCount) {
        long multiplier = 1L << Math.max(0, attemptCount - 1);
        long delaySeconds = Math.min(MAX_BACKOFF_SECONDS, retryDelaySeconds * multiplier);
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }

    private void recoverStaleProcessingEntries() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(processingTimeoutSeconds);
        List<UUID> staleEntryIds = federationInboxService.findStaleProcessingEntryIds(threshold, batchSize);
        for (UUID id : staleEntryIds) {
            log.warn("Recovering stale federation inbox entry {}", id);
            federationInboxService.recoverStaleProcessingEntry(
                id,
                "Processing timed out after " + processingTimeoutSeconds + " seconds",
                maxAttempts,
                LocalDateTime.now()
            );
        }
    }

    private Optional<RemoteActivityEnrichment> resolveEnrichment(Map<String, Object> activity, String recipientUsername) {
        if (!"Create".equals(activity.get("type"))) {
            return Optional.empty();
        }

        Object object = activity.get("object");
        if (!(object instanceof Map<?, ?> objectMap)) {
            return Optional.empty();
        }

        if (!"Note".equals(objectMap.get("type")) || objectMap.get("inReplyTo") != null) {
            return Optional.empty();
        }

        Object detailUri = objectMap.get("fitpubDetailUri");
        Object objectId = objectMap.get("id");
        if (!(detailUri instanceof String fitpubDetailUri) || !(objectId instanceof String activityUri)) {
            return Optional.empty();
        }
        if (!isSameHost(activityUri, fitpubDetailUri)) {
            log.warn("Ignoring fitpubDetailUri on different host: activityUri={}, fitpubDetailUri={}",
                activityUri, fitpubDetailUri);
            return Optional.empty();
        }
        return remoteActivityDetailsFetcher.fetch(fitpubDetailUri, recipientUsername);
    }

    private boolean isSameHost(String activityUri, String fitpubDetailUri) {
        try {
            URI activity = URI.create(activityUri);
            URI detail = URI.create(fitpubDetailUri);
            return activity.getHost() != null
                && activity.getHost().equalsIgnoreCase(detail.getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
