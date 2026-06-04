package com.pdfanalyzer.model;

/**
 * Pre-classified document type based on structural and content signals,
 * used to enrich the Gemini prompt for better structured output.
 */
public enum DocumentType {
    RESEARCH_PAPER,
    SLIDE_DECK,
    BUSINESS_REPORT,
    LEGAL_DOCUMENT,
    TECHNICAL_MANUAL,
    INVOICE_OR_FORM,
    UNKNOWN
}