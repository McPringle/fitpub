package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javahippie.fitpub.model.entity.FederationInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FederationInboxProcessor Tests")
class FederationInboxProcessorTest {

    @Mock
    private FederationInboxService federationInboxService;

    @Mock
    private InboxProcessor inboxProcessor;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FederationInboxProcessor federationInboxProcessor;

    private UUID entryId;
    private FederationInbox entry;

    @BeforeEach
    void setUp() {
        entryId = UUID.randomUUID();
        entry = FederationInbox.builder()
            .id(entryId)
            .recipientUsername("janedoe")
            .activityType("Create")
            .payloadJson("{\"type\":\"Create\"}")
            .status(FederationInbox.Status.PROCESSING)
            .attemptCount(1)
            .nextAttemptAt(LocalDateTime.now())
            .build();

        ReflectionTestUtils.setField(federationInboxProcessor, "maxAttempts", 10);
        ReflectionTestUtils.setField(federationInboxProcessor, "batchSize", 20);
        ReflectionTestUtils.setField(federationInboxProcessor, "retryDelaySeconds", 300L);
    }

    @Test
    @DisplayName("Should process claimed entry and mark it done")
    void trigger_ShouldProcessClaimedEntry() throws Exception {
        Map<String, Object> payload = Map.of(
            "type", "Create",
            "actor", "https://remote.example/users/alice"
        );

        when(federationInboxService.claimById(entryId)).thenReturn(Optional.of(entry));
        when(objectMapper.readValue(eq(entry.getPayloadJson()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenReturn(payload);

        federationInboxProcessor.trigger(entryId);

        verify(inboxProcessor).processActivity("janedoe", payload);
        verify(federationInboxService).markDone(entryId);
    }

    @Test
    @DisplayName("Should reschedule failed entry for retry")
    void trigger_ShouldRescheduleOnFailure() throws Exception {
        when(federationInboxService.claimById(entryId)).thenReturn(Optional.of(entry));
        when(objectMapper.readValue(eq(entry.getPayloadJson()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenThrow(new RuntimeException("bad payload"));

        federationInboxProcessor.trigger(entryId);

        verify(federationInboxService).markForRetry(eq(entryId), any(Exception.class), eq(10), any(LocalDateTime.class));
        verify(inboxProcessor, never()).processActivity(eq("janedoe"), any());
    }

    @Test
    @DisplayName("Should trigger due entries from scheduler")
    void processDueEntries_ShouldTriggerEachDueEntry() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FederationInbox firstEntry = FederationInbox.builder()
            .id(first)
            .recipientUsername("janedoe")
            .payloadJson("{\"type\":\"Follow\"}")
            .status(FederationInbox.Status.PROCESSING)
            .attemptCount(1)
            .nextAttemptAt(LocalDateTime.now())
            .build();
        FederationInbox secondEntry = FederationInbox.builder()
            .id(second)
            .recipientUsername("janedoe")
            .payloadJson("{\"type\":\"Like\"}")
            .status(FederationInbox.Status.PROCESSING)
            .attemptCount(1)
            .nextAttemptAt(LocalDateTime.now())
            .build();

        when(federationInboxService.findDueEntryIds(20)).thenReturn(List.of(first, second));
        when(federationInboxService.claimById(first)).thenReturn(Optional.of(firstEntry));
        when(federationInboxService.claimById(second)).thenReturn(Optional.of(secondEntry));
        when(objectMapper.readValue(eq(firstEntry.getPayloadJson()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenReturn(Map.of("type", "Follow"));
        when(objectMapper.readValue(eq(secondEntry.getPayloadJson()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenReturn(Map.of("type", "Like"));

        federationInboxProcessor.processDueEntries();

        verify(federationInboxService).claimById(first);
        verify(federationInboxService).claimById(second);
    }
}
