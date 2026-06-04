package com.pdfanalyzer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "gemini.api")
public class GeminiConfig {

    @NotBlank(message = "Gemini API key must be configured")
    private String key;

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private String model = "gemini-1.5-flash";
    private int maxOutputTokens = 1024;
    private double temperature = 0.2;

    public String getApiKey() {
        return key;
    }
}