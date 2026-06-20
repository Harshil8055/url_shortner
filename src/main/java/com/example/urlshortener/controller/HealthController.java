package com.example.urlshortener.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Temporary health-check endpoint used only to verify the Spring Boot
 * boilerplate compiles, starts, and serves requests correctly.
 * This will sit alongside (not be replaced by) UrlController, which is
 * added in the next implementation step.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
