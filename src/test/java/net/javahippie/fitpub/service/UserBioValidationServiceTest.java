package net.javahippie.fitpub.service;

import net.javahippie.fitpub.exception.ApiValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("UserBioValidationService Tests")
class UserBioValidationServiceTest {

    @Test
    @DisplayName("Should allow bios up to configured max length")
    void shouldAllowBiosUpToConfiguredMaxLength() {
        UserBioValidationService service = new UserBioValidationService(12);

        assertDoesNotThrow(() -> service.validate("123456789012"));
    }

    @Test
    @DisplayName("Should reject bios longer than configured max length")
    void shouldRejectBiosLongerThanConfiguredMaxLength() {
        UserBioValidationService service = new UserBioValidationService(12);

        assertThrows(ApiValidationException.class, () -> service.validate("1234567890123"));
    }
}
