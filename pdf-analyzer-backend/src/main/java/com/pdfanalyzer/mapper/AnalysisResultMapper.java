package com.pdfanalyzer.mapper;

import com.pdfanalyzer.dto.response.AnalysisResult;
import org.springframework.stereotype.Component;

/**
 * Maps and sanitizes raw AI analysis results.
 * Ensures all fields are safe strings before returning to the client.
 */
@Component
public class AnalysisResultMapper {

    private static final String FALLBACK = "Not Available";

    public AnalysisResult sanitize(AnalysisResult raw) {
        if (raw == null) return buildFallback();

        return AnalysisResult.builder()
                .documentType(sanitizeField(raw.getDocumentType()))
                .title(sanitizeField(raw.getTitle()))
                .authors(sanitizeField(raw.getAuthors()))
                .summary(sanitizeField(raw.getSummary()))
                .keyTakeaway(sanitizeField(raw.getKeyTakeaway()))
                .extractionStrategy(raw.getExtractionStrategy())
                .totalPages(raw.getTotalPages())
                .qualityScore(raw.getQualityScore())
                .build();
    }

    private String sanitizeField(String value) {
        if (value == null || value.isBlank()) return FALLBACK;
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("N/A")) return FALLBACK;
        return trimmed;
    }

    private AnalysisResult buildFallback() {
        return AnalysisResult.builder()
                .documentType(FALLBACK).title(FALLBACK).authors(FALLBACK)
                .summary(FALLBACK).keyTakeaway(FALLBACK)
                .build();
    }
}