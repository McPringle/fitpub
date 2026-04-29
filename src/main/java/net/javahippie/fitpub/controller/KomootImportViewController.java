package net.javahippie.fitpub.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

/**
 * Serves the Komoot import preview page.
 */
@Controller
public class KomootImportViewController {

    @GetMapping("/komoot-import")
    public String komootImportPage(Model model) {
        LocalDate today = LocalDate.now();
        model.addAttribute("pageTitle", "Komoot Import");
        model.addAttribute("defaultStartDate", today.withDayOfYear(1));
        model.addAttribute("defaultEndDate", today);
        return "activities/komoot";
    }
}
