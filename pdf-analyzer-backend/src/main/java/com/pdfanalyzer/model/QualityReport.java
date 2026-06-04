package com.pdfanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * Detailed document quality report replacing the simple HIGH/MEDIUM/LOW string.
 * Captures extraction reliability signals for each pipeline run.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualityReport {

    /** Overall quality tier */
    private final String tier;              // HIGH / MEDIUM / LOW

    /** Extracted character count after normalization */
    private final int extractedCharCount;

    /** Estimated blank or near-blank page ratio (0.0 – 1.0) */
    private final double blankPageRatio;

    /** OCR confidence score if OCR was used (0–100), null if native */
    private final Integer ocrConfidenceScore;

    /** Whether text was truncated due to token budget */
    private final boolean textTruncated;

    /** Whether chunked summarization was applied */
    private final boolean chunked;

    /** Number of chunks if chunked, null otherwise */
    private final Integer chunkCount;

    /** Coherence signal — ratio of dictionary-like words in extracted text */
    private final double textCoherenceScore;

    /** Human-readable quality note */
    private final String note;
}