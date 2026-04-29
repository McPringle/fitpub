package net.javahippie.fitpub.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal link between a FitPub activity and its originating Komoot activity.
 */
@Entity
@Table(name = "komoot_imports",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_komoot_imports_activity_id", columnNames = "activity_id"),
           @UniqueConstraint(name = "uk_komoot_imports_user_komoot_activity_id", columnNames = {"user_id", "komoot_activity_id"})
       },
       indexes = {
           @Index(name = "idx_komoot_imports_user_id", columnList = "user_id"),
           @Index(name = "idx_komoot_imports_komoot_activity_id", columnList = "komoot_activity_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KomootImport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "komoot_activity_id", nullable = false)
    private Long komootActivityId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
