package com.pdfanalyzer.mapper;

import com.pdfanalyzer.dto.response.AnalysisResult;
import org.springframework.stereotype.Component;

/**
 * AnalysisMapper is intentionally lightweight here.
 * In future iterations with database persistence, this mapper
 * will convert between AnalysisResult DTO and an AnalysisEntity.
 *
 * Structural placeholder following production-grade layered design.
 */
@Component
public class AnalysisMapper {

    public AnalysisResult sanitize(AnalysisResult result) {
        if (result == null) return null;
        return AnalysisResult.builder()
                .documentType(sanitizeField(result.getDocumentType(), "Unknown"))
                .title(sanitizeField(result.getTitle(), "Not Found"))
                .authors(sanitizeField(result.getAuthors(), "Not Found"))
                .summary(sanitizeField(result.getSummary(), "No summary available."))
                .keyTakeaway(sanitizeField(result.getKeyTakeaway(), "No key takeaway available."))
                .build();
    }

    private String sanitizeField(String value, String fallback) {
        if (value == null || value.isBlank()
                || value.equalsIgnoreCase("null")
                || value.equalsIgnoreCase("N/A")) {
            return fallback;
        }
        return value.trim();
    }
}