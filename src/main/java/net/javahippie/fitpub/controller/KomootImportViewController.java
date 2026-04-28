package net.javahippie.fitpub.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Komoot import preview page.
 */
@Controller
public class KomootImportViewController {

    @GetMapping("/komoot-import")
    public String komootImportPage(Model model) {
        model.addAttribute("pageTitle", "Komoot Import");
        return "activities/komoot";
    }
}
