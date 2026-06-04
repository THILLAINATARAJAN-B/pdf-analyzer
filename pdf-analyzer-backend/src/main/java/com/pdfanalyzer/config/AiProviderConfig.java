package com.pdfanalyzer.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class AiProviderConfig {

    public enum Provider {
        GEMINI, OPENAI, AUTO
    }

    @Value("${ai.provider:auto}")
    private String provider;

    public Provider resolvedProvider() {
        try {
            return Provider.valueOf(provider.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Provider.AUTO;
        }
    }
}
