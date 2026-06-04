package com.pdfanalyzer.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves Tesseract tessdata directory across Docker, Linux, macOS, and Windows.
 */
@Slf4j
public final class TessdataPathResolver {

    private static final String ENG_TRAINED_DATA = "eng.traineddata";

    private TessdataPathResolver() {
    }

    public static String resolve(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank() && hasEngData(configuredPath)) {
            return configuredPath;
        }

        String fromEnv = firstNonBlank(
                System.getenv("TESSERACT_DATA_PATH"),
                System.getenv("TESSDATA_PREFIX"));
        if (fromEnv != null && hasEngData(fromEnv)) {
            log.info("Using tessdata from environment: {}", fromEnv);
            return fromEnv;
        }

        for (String candidate : defaultCandidates()) {
            if (hasEngData(candidate)) {
                log.info("Auto-detected tessdata path: {}", candidate);
                return candidate;
            }
        }

        log.warn("Tesseract tessdata not found. OCR will be unavailable until tessdata is installed.");
        return configuredPath != null && !configuredPath.isBlank()
                ? configuredPath
                : "/usr/share/tesseract-ocr/4.00/tessdata";
    }

    public static boolean isAvailable(String tessdataPath) {
        return hasEngData(tessdataPath);
    }

    private static List<String> defaultCandidates() {
        return List.of(
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/local/share/tessdata",
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata"
        );
    }

    private static boolean hasEngData(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path trainedData = Path.of(path, ENG_TRAINED_DATA);
        return Files.isRegularFile(trainedData);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim().replace('/', File.separatorChar);
            }
        }
        return null;
    }
}
