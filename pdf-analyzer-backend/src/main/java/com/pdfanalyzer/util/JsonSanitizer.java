package com.pdfanalyzer.util;

import com.pdfanalyzer.exception.AiServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes raw Gemini text output to extract a clean JSON object.
 *
 * Acts as a fallback layer when Gemini returns JSON wrapped
 * in markdown code fences, explanatory text, or extra whitespace,
 * despite being instructed otherwise.
 *
 * This is a secondary defense — the primary defense is
 * responseMimeType: "application/json" in the request body.
 */
@Slf4j
@Component
public class JsonSanitizer {

    // Matches the first { ... } block in the string
    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL);

    /**
     * Extracts the first valid JSON object from a raw text response.
     * Handles:
     * - Markdown code fences (```json ... ```)
     * - Leading/trailing whitespace and non-JSON text
     * - Escaped characters and multi-line JSON
     */
    public String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiServiceException("AI returned an empty response.");
        }

        // If the response is already clean JSON, return it directly
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // Strip markdown code fences
        String stripped = trimmed
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            return stripped;
        }

        // Extract first JSON object with regex
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(stripped);
        if (matcher.find()) {
            String extracted = matcher.group();
            log.debug("JSON extracted from mixed response ({} chars)", extracted.length());
            return extracted;
        }

        log.error("No JSON object found in AI response. Raw (first 300 chars): {}",
                raw.length() > 300 ? raw.substring(0, 300) : raw);
        throw new AiServiceException(
                "AI returned a response with no valid JSON object. Please retry.");
    }
}