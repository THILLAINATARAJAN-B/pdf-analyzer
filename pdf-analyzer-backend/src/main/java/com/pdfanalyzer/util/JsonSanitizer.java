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

    String trimmed = raw.strip();

    // Strip markdown fences first
    trimmed = trimmed.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").strip();

    // Find outermost { ... } using brace counting (handles any nesting depth)
    int start = trimmed.indexOf('{');
    if (start == -1) {
        log.error("No JSON object found. Raw (first 300): {}",
            trimmed.substring(0, Math.min(300, trimmed.length())));
        throw new AiServiceException("AI returned a response with no valid JSON object. Please retry.");
    }

    int depth = 0;
    int end = -1;
    for (int i = start; i < trimmed.length(); i++) {
        char c = trimmed.charAt(i);
        if (c == '{') depth++;
        else if (c == '}') {
            depth--;
            if (depth == 0) { end = i; break; }
        }
    }

    if (end == -1) {
        // Truncated response — brace never closed, token limit hit
        log.error("Truncated JSON from AI (unclosed brace). Raw length: {}. Increase max-output-tokens.", trimmed.length());
        throw new AiServiceException("AI response was truncated. Please retry.");
    }

    return trimmed.substring(start, end + 1);
}
}