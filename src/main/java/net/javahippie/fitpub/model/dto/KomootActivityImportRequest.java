package net.javahippie.fitpub.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for importing one specific Komoot activity.
 *
 * <p>The password is only used for the current request and is never persisted.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomootActivityImportRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    @NotBlank
    @Pattern(regexp = "\\d+", message = "Komoot user ID must contain digits only")
    private String userId;

    @NotNull
    private Long activityId;
}
