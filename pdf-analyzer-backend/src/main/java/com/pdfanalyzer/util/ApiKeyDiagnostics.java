package com.pdfanalyzer.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility for safe, structured API key status logging.
 * Never logs the raw key — only its length and a short prefix.
 */
@Slf4j
public final class ApiKeyDiagnostics {

    private ApiKeyDiagnostics() {}

    /**
     * Logs a masked status line for the given key.
     * Examples:
     *   ✅  API Key  : AIzaSy... (39 chars)
     *   ⚠   API Key  : MISSING — set GEMINI_API_KEY environment variable
     *   ⚠   API Key  : Unresolved placeholder ${GEMINI_API_KEY}
     */
    public static void logKeyStatus(String envVarName, String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            log.warn("  ⚠  API Key  : MISSING — set {} environment variable", envVarName);
            return;
        }
        if (keyValue.startsWith("${")) {
            log.warn("  ⚠  API Key  : Unresolved placeholder {} — set environment variable",
                    keyValue);
            return;
        }
        String masked = keyValue.substring(0, Math.min(8, keyValue.length())) + "...";
        log.info("  ✅  API Key  : {} ({} chars)", masked, keyValue.length());
    }
}