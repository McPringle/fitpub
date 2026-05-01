package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central validation policy for activity titles.
 */
@Service
public class ActivityTitleValidationService {

    private final int maxLength;

    public ActivityTitleValidationService(
        @Value("${fitpub.activity.title.max-length:200}") int maxLength
    ) {
        this.maxLength = maxLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void validate(String title) {
        if (title != null && title.length() > maxLength) {
            throw new ApiValidationException(
                "Title must not exceed " + maxLength + " characters"
            );
        }
    }
}
