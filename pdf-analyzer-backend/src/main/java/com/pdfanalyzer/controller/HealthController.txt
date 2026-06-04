package com.pdfanalyzer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public Map<String, Object> home() {

        return Map.of(
                "application", "PDF Analyzer Backend",
                "status", "RUNNING",
                "timestamp", Instant.now()
        );
    }
}