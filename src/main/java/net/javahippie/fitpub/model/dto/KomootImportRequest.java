package net.javahippie.fitpub.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for fetching completed activities from Komoot.
 *
 * <p>The password is only used for the current request and is never persisted.</p>
 */
public record KomootImportRequest(
        @NotBlank
        @Email
        String email,

        @NotBlank
        String password,

        @NotBlank
        @Pattern(regexp = "\\d+", message = "Komoot user ID must contain digits only")
        String userId
) {
}
