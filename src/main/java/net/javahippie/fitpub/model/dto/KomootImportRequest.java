package net.javahippie.fitpub.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

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
        String userId,

        LocalDate startDate,

        LocalDate endDate
) {
    public KomootImportRequest {
        boolean onlyOneDateProvided = (startDate == null) != (endDate == null);
        if (onlyOneDateProvided) {
            throw new IllegalArgumentException("Start date and end date must either both be set or both be empty.");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be before or equal to end date.");
        }
    }
}
