package com.pdfanalyzer.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of PDF structural inspection.
 * Carries all signals needed by PdfExtractionOrchestrator
 * to select the correct extraction strategy.
 */
@Getter
@Builder
public class PdfInspectionResult {

    private final int totalPages;
    private final int textPageCount;
    private final int imageOnlyPageCount;
    private final int extractedCharCount;
    private final boolean isPasswordProtected;
    private final boolean hasEmbeddedText;
    private final boolean isLowTextDensity;
    private final ExtractionStrategy recommendedStrategy;
    private final DocumentType preclassifiedType;

    /**
     * Returns true if this PDF is a slide-type document
     * (few pages, low text density, many image regions).
     */
    public boolean isSlideDeck() {
        return preclassifiedType == DocumentType.SLIDE_DECK;
    }

    /**
     * Returns true if native extraction alone is reliable.
     */
    public boolean isNativeExtractionReliable() {
        return recommendedStrategy == ExtractionStrategy.NATIVE;
    }
}