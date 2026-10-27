package com.example.casc.web;

import com.example.casc.profile.SchemaProfile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the wizard shell. Wizard steps arrive in Phase 5. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("profile", SchemaProfile.provisional());
        return "index";
    }
}
