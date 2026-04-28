package net.javahippie.fitpub.model.dto;

import java.time.OffsetDateTime;

/**
 * Reduced activity representation returned by the Komoot import preview.
 */
public record KomootActivitySummaryDTO(
        long id,
        String name,
        String sport,
        String mappedActivityType,
        String status,
        String type,
        OffsetDateTime date,
        Double distanceMeters,
        Integer durationSeconds,
        Integer timeInMotionSeconds,
        Double elevationUp,
        boolean imported
) {
}
