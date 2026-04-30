package net.javahippie.fitpub.controller;

import lombok.RequiredArgsConstructor;
import net.javahippie.fitpub.config.KomootSupport;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes global model attributes required by shared layouts.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final KomootSupport komootSupport;

    @ModelAttribute("komootSupportEnabled")
    public boolean komootSupportEnabled() {
        return komootSupport.isEnabled();
    }
}
