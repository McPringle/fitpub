package net.javahippie.fitpub.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response for importing exactly one Komoot activity into FitPub.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomootImportExecutionResponse {

    private UUID importedActivityId;
    private Long importedKomootActivityId;
    private String status;
    private String message;
}
