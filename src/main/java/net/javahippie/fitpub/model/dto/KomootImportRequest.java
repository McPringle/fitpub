package net.javahippie.fitpub.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request payload for fetching completed activities from Komoot.
 *
 * <p>The password is only used for the current request and is never persisted.</p>
 */
@Data
@Builder
@NoArgsConstructor
public class KomootImportRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    @NotBlank
    @Pattern(regexp = "\\d+", message = "Komoot user ID must contain digits only")
    private String userId;

    private LocalDate startDate;

    private LocalDate endDate;

    public KomootImportRequest(String email, String password, String userId, LocalDate startDate, LocalDate endDate) {
        this.email = email;
        this.password = password;
        this.userId = userId;
        this.startDate = startDate;
        this.endDate = endDate;
        validateDateRange();
    }

    @AssertTrue(message = "Start date and end date must either both be set or both be empty, and start date must be before or equal to end date.")
    public boolean isDateRangeConsistent() {
        try {
            validateDateRange();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateDateRange() {
        boolean onlyOneDateProvided = (startDate == null) != (endDate == null);
        if (onlyOneDateProvided) {
            throw new IllegalArgumentException("Start date and end date must either both be set or both be empty.");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be before or equal to end date.");
        }
    }
}
