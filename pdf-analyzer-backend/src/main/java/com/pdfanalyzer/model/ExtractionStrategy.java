package com.pdfanalyzer.model;

/**
 * Represents the extraction strategy selected by PdfInspectionService
 * based on the document's structural characteristics.
 */
public enum ExtractionStrategy {
    /**
     * Native PDFBox text extraction — sufficient text is embedded.
     */
    NATIVE,

    /**
     * OCR-only extraction — no embedded text; document is image-based or scanned.
     */
    OCR,

    /**
     * Hybrid extraction — some embedded text exists but quality is low;
     * OCR supplements native extraction.
     */
    HYBRID
}