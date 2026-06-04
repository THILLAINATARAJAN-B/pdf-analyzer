package com.pdfanalyzer.util;

import com.pdfanalyzer.exception.AiServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JsonSanitizer {

    // Matches the first { ... } block, including nested braces
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL);

    /**
     * Strips markdown code fences and extracts the raw JSON object
     * from potentially noisy AI output.
     */
    public String extractJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new AiServiceException("AI returned empty content.");
        }

        // Remove markdown code fences (```json ... ``` or ``` ... ```)
        String cleaned = rawText
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        // If the output starts directly with {, return as-is
        if (cleaned.startsWith("{")) {
            int lastBrace = cleaned.lastIndexOf('}');
            if (lastBrace > 0) {
                return cleaned.substring(0, lastBrace + 1).trim();
            }
        }

        // Otherwise extract the JSON block using regex
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            return matcher.group().trim();
        }

        log.error("Could not extract JSON from AI response. Preview: {}",
                cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned);
        throw new AiServiceException(
                "AI returned a response that could not be parsed as structured data.");
    }
}