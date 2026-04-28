package net.javahippie.fitpub.model.dto;

import java.util.List;

/**
 * Response payload for the Komoot import preview.
 */
public record KomootActivitiesResponse(
        String userId,
        int totalCount,
        List<KomootActivitySummaryDTO> activities
) {
}
