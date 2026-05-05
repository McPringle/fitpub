package net.javahippie.fitpub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.FederationInbox;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final InboxProcessor inboxProcessor;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${fitpub.activitypub.inbox.max-attempts:10}")
    private int maxAttempts;

    @org.springframework.beans.factory.annotation.Value("${fitpub.activitypub.inbox.batch-size:20}")
    private int batchSize;

    @org.springframework.beans.factory.annotation.Value("${fitpub.activitypub.inbox.retry-delay-seconds:300}")
    private long retryDelaySeconds;

    public void trigger(UUID inboxEntryId) {
        federationInboxService.claimById(inboxEntryId)
            .ifPresent(this::processClaimedEntry);
    }

    @Scheduled(fixedDelayString = "${fitpub.activitypub.inbox.processing-interval-ms:300000}")
    public void processDueEntries() {
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
            inboxProcessor.processActivity(entry.getRecipientUsername(), activity);
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
}
