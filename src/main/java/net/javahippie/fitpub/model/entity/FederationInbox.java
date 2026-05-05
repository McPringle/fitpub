package net.javahippie.fitpub.model.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable queue entry for inbound ActivityPub deliveries.
 */
@Entity
@Table(name = "federation_inbox", indexes = {
    @Index(name = "idx_federation_inbox_status_next_attempt", columnList = "status,next_attempt_at"),
    @Index(name = "idx_federation_inbox_recipient_status", columnList = "recipient_username,status"),
    @Index(name = "idx_federation_inbox_actor_uri", columnList = "actor_uri"),
    @Index(name = "idx_federation_inbox_object_uri", columnList = "object_uri"),
    @Index(name = "idx_federation_inbox_received_at", columnList = "received_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FederationInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "recipient_username", nullable = false, length = 255)
    private String recipientUsername;

    @Column(name = "activity_type", nullable = false, length = 50)
    private String activityType;

    @Column(name = "actor_uri", length = 512)
    private String actorUri;

    @Column(name = "object_uri", length = 512)
    private String objectUri;

    @Type(JsonBinaryType.class)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = Status.PENDING;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = LocalDateTime.now();
        }
    }

    public enum Status {
        PENDING,
        PROCESSING,
        DONE,
        ERROR
    }
}
