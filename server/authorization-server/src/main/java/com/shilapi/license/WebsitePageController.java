package com.shilapi.license;

import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class WebsitePageController {
    static final Set<String> PUBLIC_PATHS = Set.of(
        "/", "/index.html", "/downloads.json",
        "/assets/site.css", "/assets/site.js", "/assets/theme.js", "/assets/api.js",
        "/assets/logo-light.png", "/assets/logo-dark.png", "/assets/app-icon.png"
    );

    @GetMapping({"/", "/index.html"}) ResponseEntity<ClassPathResource> page() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(new ClassPathResource("static/index.html"));
    }
}
