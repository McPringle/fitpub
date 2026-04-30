package net.javahippie.fitpub.controller;

import lombok.RequiredArgsConstructor;
import net.javahippie.fitpub.config.KomootSupport;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

/**
 * Serves the Komoot import preview page.
 */
@Controller
@RequiredArgsConstructor
public class KomootImportViewController {

    private final KomootSupport komootSupport;

    @GetMapping("/komoot-import")
    public String komootImportPage(Model model) {
        if (!komootSupport.isEnabled()) {
            model.addAttribute("pageTitle", "Komoot Import Unavailable");
            model.addAttribute("featureName", "Komoot Import");
            model.addAttribute("featureMessage", "Komoot support is currently disabled on this instance.");
            model.addAttribute("featureIcon", "bi bi-signpost-split text-secondary");
            return "feature-disabled";
        }

        LocalDate today = LocalDate.now();
        model.addAttribute("pageTitle", "Komoot Import");
        model.addAttribute("defaultStartDate", today.withDayOfYear(1));
        model.addAttribute("defaultEndDate", today);
        return "activities/komoot";
    }
}
