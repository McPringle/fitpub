package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javahippie.fitpub.model.entity.FederationInbox;
import net.javahippie.fitpub.repository.FederationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FederationInboxService Tests")
class FederationInboxServiceTest {

    @Mock
    private FederationInboxRepository federationInboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FederationInboxService federationInboxService;

    private Map<String, Object> activity;

    @BeforeEach
    void setUp() {
        activity = Map.of(
            "type", "Create",
            "actor", "https://remote.example/users/alice",
            "object", Map.of(
                "id", "https://remote.example/activities/123",
                "type", "Note"
            )
        );
    }

    @Test
    @DisplayName("Should enqueue durable inbox entry from validated activity")
    void enqueue_ShouldPersistInboxEntry() throws Exception {
        when(objectMapper.writeValueAsString(activity)).thenReturn("{\"type\":\"Create\"}");
        when(federationInboxRepository.save(org.mockito.ArgumentMatchers.any(FederationInbox.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        FederationInbox entry = federationInboxService.enqueue("janedoe", activity);

        ArgumentCaptor<FederationInbox> captor = ArgumentCaptor.forClass(FederationInbox.class);
        verify(federationInboxRepository).save(captor.capture());

        FederationInbox saved = captor.getValue();
        assertThat(saved.getRecipientUsername()).isEqualTo("janedoe");
        assertThat(saved.getActivityType()).isEqualTo("Create");
        assertThat(saved.getActorUri()).isEqualTo("https://remote.example/users/alice");
        assertThat(saved.getObjectUri()).isEqualTo("https://remote.example/activities/123");
        assertThat(saved.getPayloadJson()).isEqualTo("{\"type\":\"Create\"}");
        assertThat(saved.getStatus()).isEqualTo(FederationInbox.Status.PENDING);
        assertThat(saved.getAttemptCount()).isEqualTo(0);
        assertThat(saved.getNextAttemptAt()).isNotNull();
        assertThat(entry.getRecipientUsername()).isEqualTo("janedoe");
    }

    @Test
    @DisplayName("Should mark inbox entry as done")
    void markDone_ShouldSetDoneStatusAndProcessedAt() {
        UUID id = UUID.randomUUID();
        FederationInbox entry = FederationInbox.builder()
            .id(id)
            .recipientUsername("janedoe")
            .activityType("Create")
            .payloadJson("{}")
            .status(FederationInbox.Status.PENDING)
            .nextAttemptAt(LocalDateTime.now())
            .build();

        when(federationInboxRepository.findById(id)).thenReturn(java.util.Optional.of(entry));

        federationInboxService.markDone(id);

        assertThat(entry.getStatus()).isEqualTo(FederationInbox.Status.DONE);
        assertThat(entry.getProcessedAt()).isNotNull();
        assertThat(entry.getLastError()).isNull();
    }
}
