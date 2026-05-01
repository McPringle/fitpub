package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central validation policy for activity descriptions.
 */
@Service
public class ActivityDescriptionValidationService {

    private final int maxLength;

    public ActivityDescriptionValidationService(
        @Value("${fitpub.activity.description.max-length:5000}") int maxLength
    ) {
        this.maxLength = maxLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void validate(String description) {
        if (description != null && description.length() > maxLength) {
            throw new ApiValidationException(
                "Description must not exceed " + maxLength + " characters"
            );
        }
    }
}
