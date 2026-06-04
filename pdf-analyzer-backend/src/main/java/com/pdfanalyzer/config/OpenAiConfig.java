package com.pdfanalyzer.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class OpenAiConfig {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${openai.api.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.api.max-output-tokens:1024}")
    private int maxOutputTokens;

    @Value("${openai.api.temperature:0.2}")
    private double temperature;
}
