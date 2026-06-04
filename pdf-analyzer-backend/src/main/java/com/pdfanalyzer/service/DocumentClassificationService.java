package com.pdfanalyzer.service;

import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.model.PdfInspectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Refines document type classification after full text extraction.
 * Returns a human-readable type hint injected into the Gemini prompt
 * to improve structured output accuracy.
 *
 * Two-pass classification:
 * Pass 1 — Use structural inspection result if non-UNKNOWN (fast path).
 * Pass 2 — Score-based analysis of the full extracted text.
 *
 * WHY score-based for research papers:
 * Smart sampling extracts first 3 + last 2 pages, so "references" IS
 * included in the sample (last 2 pages). However, truncation or garbled
 * reference lists can still cause lower.contains("references") to miss.
 * A weighted multi-signal approach is significantly more robust.
 */
@Slf4j
@Service
public class DocumentClassificationService {

    public String classify(String extractedText, PdfInspectionResult inspection) {

        // Pass 1 — Trust structural pre-classification if confident
        DocumentType structuralType = inspection.getPreclassifiedType();
        if (structuralType != DocumentType.UNKNOWN) {
            log.info("Document type from structural inspection: {}", structuralType);
            return formatType(structuralType);
        }

        // Pass 2 — Score-based full-text analysis
        if (extractedText == null || extractedText.isBlank()) {
            return "Unknown Document";
        }

        String lower = extractedText.toLowerCase();

        // ── Research Paper Score ──────────────────────────────────────────────
        // Multiple weighted signals — any 3 academic markers is a strong classification.
        // This avoids the fragile single-condition "abstract AND references" check.
        boolean hasAbstract    = lower.contains("abstract");
        boolean hasReferences  = lower.contains("references")
                              || lower.contains("bibliography")
                              || lower.contains("works cited");
        boolean hasIntro       = lower.contains("introduction");
        boolean hasConclusion  = lower.contains("conclusion")
                              || lower.contains("discussion");
        boolean hasDoi         = lower.contains("doi:") || lower.contains("arxiv")
                              || lower.contains("arxiv.org");
        boolean hasEtAl        = lower.contains("et al.");
        boolean hasFigure      = lower.contains("figure") || lower.contains("fig.");
        boolean hasAcademic    = lower.contains("table") || lower.contains("section")
                              || lower.contains("keywords");

        int researchScore = (hasAbstract   ? 2 : 0)
                          + (hasReferences ? 2 : 0)
                          + (hasIntro      ? 1 : 0)
                          + (hasConclusion ? 1 : 0)
                          + (hasDoi        ? 2 : 0)
                          + (hasEtAl       ? 2 : 0)
                          + (hasFigure     ? 1 : 0)
                          + (hasAcademic   ? 1 : 0);

        if (researchScore >= 4) {
            log.info("Classified as RESEARCH_PAPER (score={})", researchScore);
            return formatType(DocumentType.RESEARCH_PAPER);
        }

        // ── Other document types ──────────────────────────────────────────────
        if (lower.contains("agenda") || lower.contains("presented by")
                || lower.contains("slide") || lower.contains("click to edit")) {
            return formatType(DocumentType.SLIDE_DECK);
        }
        if (lower.contains("executive summary") || lower.contains("quarterly report")
                || lower.contains("fiscal year") || lower.contains("annual report")) {
            return formatType(DocumentType.BUSINESS_REPORT);
        }
        if (lower.contains("terms and conditions") || lower.contains("clause")
                || lower.contains("party agrees") || lower.contains("hereinafter")
                || lower.contains("whereas")) {
            return formatType(DocumentType.LEGAL_DOCUMENT);
        }
        if (lower.contains("installation") || lower.contains("configuration")
                || lower.contains("user guide") || lower.contains("getting started")) {
            return formatType(DocumentType.TECHNICAL_MANUAL);
        }
        if (lower.contains("invoice") || lower.contains("payable")
                || lower.contains("receipt") || lower.contains("bill to")) {
            return formatType(DocumentType.INVOICE_OR_FORM);
        }

        return formatType(DocumentType.UNKNOWN);
    }

    private String formatType(DocumentType type) {
        return switch (type) {
            case RESEARCH_PAPER   -> "Research Paper";
            case SLIDE_DECK       -> "Slide Deck / Presentation";
            case BUSINESS_REPORT  -> "Business Report";
            case LEGAL_DOCUMENT   -> "Legal Document";
            case TECHNICAL_MANUAL -> "Technical Manual";
            case INVOICE_OR_FORM  -> "Invoice or Form";
            case UNKNOWN          -> "General Document";
        };
    }
}