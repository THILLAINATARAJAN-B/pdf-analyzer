package com.pdfanalyzer.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Logs API key load status for startup verification.
 * Never logs full secrets — only length and partial prefix/suffix.
 */
@Slf4j
public final class ApiKeyDiagnostics {

    private ApiKeyDiagnostics() {
    }

    public static void logKeyStatus(String keyName, String key) {
        if (key == null || key.isBlank()) {
            log.info("  {} : NOT SET (blank or missing from environment/.env)", keyName);
            return;
        }

        if (key.startsWith("${")) {
            log.warn("  {} : UNRESOLVED PLACEHOLDER — value is \"{}\"", keyName, key);
            return;
        }

        String prefix = key.substring(0, Math.min(12, key.length()));
        String suffix = key.length() > 4 ? key.substring(key.length() - 4) : key;

        log.info("  {} : LOADED (length={}, startsWith=\"{}\", endsWith=\"{}\")",
                keyName, key.length(), prefix, suffix);
    }
}
