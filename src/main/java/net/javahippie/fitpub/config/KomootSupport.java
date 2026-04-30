package net.javahippie.fitpub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central support flag for Komoot integration availability.
 */
@Component
public class KomootSupport {

    private final boolean enabled;

    public KomootSupport(@Value("${fitpub.komoot.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
