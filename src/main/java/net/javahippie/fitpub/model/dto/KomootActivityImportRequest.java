package net.javahippie.fitpub.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for importing one specific Komoot activity.
 *
 * <p>The password is only used for the current request and is never persisted.</p>
 */
public record KomootActivityImportRequest(
        @NotBlank
        @Email
        String email,

        @NotBlank
        String password,

        @NotBlank
        @Pattern(regexp = "\\d+", message = "Komoot user ID must contain digits only")
        String userId,

        @NotNull
        Long activityId
) {
}
