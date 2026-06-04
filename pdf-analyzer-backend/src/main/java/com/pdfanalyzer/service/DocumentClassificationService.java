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
 * Combines structural inspection signals with extracted text signals
 * for a two-pass classification result.
 */
@Slf4j
@Service
public class DocumentClassificationService {

    public String classify(String extractedText, PdfInspectionResult inspection) {
        DocumentType structuralType = inspection.getPreclassifiedType();

        // If inspection already identified a strong type, use it
        if (structuralType != DocumentType.UNKNOWN) {
            log.info("Document type from structural inspection: {}", structuralType);
            return formatType(structuralType);
        }

        // Refine with full-text signals
        if (extractedText == null || extractedText.isBlank()) {
            return "Unknown Document";
        }

        String lower = extractedText.toLowerCase();

        if (lower.contains("abstract") && (lower.contains("conclusion") || lower.contains("references"))) {
            return formatType(DocumentType.RESEARCH_PAPER);
        }
        if (lower.contains("agenda") || lower.contains("slide") || lower.contains("presented by")) {
            return formatType(DocumentType.SLIDE_DECK);
        }
        if (lower.contains("executive summary") || lower.contains("quarterly") || lower.contains("fiscal year")) {
            return formatType(DocumentType.BUSINESS_REPORT);
        }
        if (lower.contains("terms and conditions") || lower.contains("clause") || lower.contains("party agrees")) {
            return formatType(DocumentType.LEGAL_DOCUMENT);
        }
        if (lower.contains("installation") || lower.contains("configuration") || lower.contains("user guide")) {
            return formatType(DocumentType.TECHNICAL_MANUAL);
        }
        if (lower.contains("invoice") || lower.contains("payable") || lower.contains("receipt")) {
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