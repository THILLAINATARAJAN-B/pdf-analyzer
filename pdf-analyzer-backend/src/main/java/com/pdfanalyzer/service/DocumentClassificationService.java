package com.pdfanalyzer.service;

import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.model.PdfInspectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Second-pass classifier using full extracted text.
 * Only runs when PdfInspectionService returned UNKNOWN.
 */
@Slf4j
@Service
public class DocumentClassificationService {

    public String classify(String extractedText, PdfInspectionResult inspection) {
        DocumentType structuralType = inspection.getPreclassifiedType();

        if (structuralType != DocumentType.UNKNOWN) {
            log.info("Document type from structural inspection: {}", structuralType);
            return formatType(structuralType);
        }

        if (extractedText == null || extractedText.isBlank()) {
            return formatType(DocumentType.UNKNOWN);
        }

        String lower = extractedText.toLowerCase();

        // Research Paper — academic structure signals
        boolean hasAbstract   = lower.contains("abstract");
        boolean hasReferences = lower.contains("references") || lower.contains("bibliography");
        boolean hasConclusion = lower.contains("conclusion");
        if (hasAbstract && (hasReferences || hasConclusion)) {
            return formatType(DocumentType.RESEARCH_PAPER);
        }

        // Government / Tax
        if (lower.contains("internal revenue service") || lower.contains("irs")
                || lower.contains("department of the treasury")
                || lower.contains("form 1040") || lower.contains("taxpayer")) {
            return formatType(DocumentType.GOVERNMENT_DOCUMENT);
        }

        // Business Report
        if (lower.contains("executive summary") || lower.contains("quarterly")
                || lower.contains("fiscal year") || lower.contains("earnings")) {
            return formatType(DocumentType.BUSINESS_REPORT);
        }

        // Slide Deck
        if (lower.contains("agenda") || lower.contains("presented by")
                || lower.contains("thank you for listening")) {
            return formatType(DocumentType.SLIDE_DECK);
        }

        // Legal
        if (lower.contains("terms and conditions") || lower.contains("clause")
                || lower.contains("party agrees") || lower.contains("hereinafter")) {
            return formatType(DocumentType.LEGAL_DOCUMENT);
        }

        // Technical Manual
        if (lower.contains("installation") || lower.contains("configuration")
                || lower.contains("user guide") || lower.contains("troubleshooting")) {
            return formatType(DocumentType.TECHNICAL_MANUAL);
        }

        // Invoice / Form
        if (lower.contains("invoice") || lower.contains("payable")
                || lower.contains("bill to") || lower.contains("purchase order")) {
            return formatType(DocumentType.INVOICE_OR_FORM);
        }
        // In DocumentClassificationService.java — add these signals
        if (lower.contains("introduction") && lower.contains("methodology")) {
            return formatType(DocumentType.RESEARCH_PAPER);
        }
        if (lower.contains("figure") && lower.contains("table") && lower.contains("section")) {
            return formatType(DocumentType.RESEARCH_PAPER);
        }

        return formatType(DocumentType.UNKNOWN);
    }

    private String formatType(DocumentType type) {
        return switch (type) {
            case RESEARCH_PAPER      -> "Research Paper";
            case SLIDE_DECK          -> "Slide Deck / Presentation";
            case BUSINESS_REPORT     -> "Business Report";
            case LEGAL_DOCUMENT      -> "Legal Document";
            case TECHNICAL_MANUAL    -> "Technical Manual";
            case INVOICE_OR_FORM     -> "Invoice or Form";
            case GOVERNMENT_DOCUMENT -> "Government / Tax Document";
            case UNKNOWN             -> "General Document";
        };
    }
}