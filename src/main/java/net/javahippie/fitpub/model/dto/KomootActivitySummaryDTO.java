package net.javahippie.fitpub.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reduced activity representation returned by the Komoot import preview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomootActivitySummaryDTO {

    private long id;
    private String name;
    private String sport;
    private String mappedActivityType;
    private String status;
    private String type;
    private OffsetDateTime date;
    private Double distanceMeters;
    private Integer durationSeconds;
    private Integer timeInMotionSeconds;
    private Double elevationUp;
    private boolean imported;
    private UUID fitPubActivityId;
}
