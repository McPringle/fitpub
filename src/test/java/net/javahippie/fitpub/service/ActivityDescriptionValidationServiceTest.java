package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ActivityDescriptionValidationService Tests")
class ActivityDescriptionValidationServiceTest {

    @Test
    @DisplayName("Should allow descriptions up to configured max length")
    void shouldAllowDescriptionsUpToConfiguredMaxLength() {
        ActivityDescriptionValidationService service = new ActivityDescriptionValidationService(20);

        assertDoesNotThrow(() -> service.validate("12345678901234567890"));
    }

    @Test
    @DisplayName("Should reject descriptions longer than configured max length")
    void shouldRejectDescriptionsLongerThanConfiguredMaxLength() {
        ActivityDescriptionValidationService service = new ActivityDescriptionValidationService(20);

        assertThrows(ApiValidationException.class, () -> service.validate("123456789012345678901"));
    }
}
