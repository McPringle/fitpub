package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ActivityTitleValidationService Tests")
class ActivityTitleValidationServiceTest {

    @Test
    @DisplayName("Should allow titles up to configured max length")
    void shouldAllowTitlesUpToConfiguredMaxLength() {
        ActivityTitleValidationService service = new ActivityTitleValidationService(10);

        assertDoesNotThrow(() -> service.validate("1234567890"));
    }

    @Test
    @DisplayName("Should reject titles longer than configured max length")
    void shouldRejectTitlesLongerThanConfiguredMaxLength() {
        ActivityTitleValidationService service = new ActivityTitleValidationService(10);

        assertThrows(ApiValidationException.class, () -> service.validate("12345678901"));
    }
}
