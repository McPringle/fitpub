package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central validation policy for user bio fields.
 */
@Service
public class UserBioValidationService {

    private final int maxLength;

    public UserBioValidationService(@Value("${fitpub.user.bio.max-length:500}") int maxLength) {
        this.maxLength = maxLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void validate(String bio) {
        if (bio != null && bio.length() > maxLength) {
            throw new ApiValidationException("Bio must not exceed " + maxLength + " characters");
        }
    }
}
