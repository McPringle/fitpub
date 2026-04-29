package net.javahippie.fitpub.model.dto;

import java.util.UUID;

/**
 * Response for importing exactly one Komoot activity into FitPub.
 */
public record KomootImportExecutionResponse(
        UUID importedActivityId,
        Long importedKomootActivityId,
        String status,
        String message
) {
}
