package com.shilapi.license;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AdminPageController {
    @GetMapping("/admin/") ResponseEntity<ClassPathResource> page() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(new ClassPathResource("static/admin/index.html"));
    }
}
