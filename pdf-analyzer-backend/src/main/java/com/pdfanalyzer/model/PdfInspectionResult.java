package com.pdfanalyzer.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Structural inspection result.
 * Removed imageOnlyPageCount and textPageCount — those existed
 * only to feed DocumentQualityService which is now removed.
 */
@Getter
@Builder
public class PdfInspectionResult {

    private final int totalPages;
    private final int extractedCharCount;
    private final boolean isPasswordProtected;
    private final boolean hasEmbeddedText;
    private final boolean isLowTextDensity;
    private final ExtractionStrategy recommendedStrategy;
    private final DocumentType preclassifiedType;

    public boolean isSlideDeck() {
        return preclassifiedType == DocumentType.SLIDE_DECK;
    }

    public boolean isNativeExtractionReliable() {
        return recommendedStrategy == ExtractionStrategy.NATIVE;
    }
}