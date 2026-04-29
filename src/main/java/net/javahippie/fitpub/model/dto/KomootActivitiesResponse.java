package net.javahippie.fitpub.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response payload for the Komoot import preview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomootActivitiesResponse {

    private String userId;
    private int totalCount;
    private List<KomootActivitySummaryDTO> activities;
}
