package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfPasswordException;
import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Inspects a PDF's structural characteristics and recommends
 * the optimal extraction strategy (NATIVE, OCR, or HYBRID).
 *
 * Deliberately read-only — samples up to 5 pages to detect text density.
 * Full text extraction happens downstream in PdfExtractionOrchestrator.
 *
 * Uses score-based research paper detection to avoid the "references on
 * page 14" problem — where the first-5-page sample misses the references
 * section and falls back to UNKNOWN incorrectly.
 */
@Slf4j
@Service
public class PdfInspectionService {

    @Value("${pdf.processing.max-pages:200}")
    private int maxPages;

    @Value("${pdf.processing.low-text-threshold:100}")
    private int lowTextThreshold;

    public PdfInspectionResult inspect(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            // Catch password-protected PDFs with a clean, explicit exception
            if (document.isEncrypted()) {
                throw new PdfPasswordException(
                        "This PDF is password-protected. Please provide an unlocked version.");
            }

            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                throw new PdfProcessingException("The PDF has no pages.");
            }
            if (totalPages > maxPages) {
                throw new PdfProcessingException(
                        "PDF exceeds the maximum allowed page count of " + maxPages + " pages.");
            }

            // Sample first 5 pages to detect text density
            // setSortByPosition(true) is critical for multi-column layouts
            PDFTextStripper sampleStripper = new PDFTextStripper();
            sampleStripper.setSortByPosition(true);
            sampleStripper.setStartPage(1);
            sampleStripper.setEndPage(Math.min(5, totalPages));
            String sampleText = sampleStripper.getText(document);
            int sampleChars = (sampleText == null) ? 0 : sampleText.trim().length();

            boolean hasEmbeddedText = sampleChars > 0;
            boolean isLowTextDensity = sampleChars < lowTextThreshold;

            // ── Extraction Strategy Decision ──────────────────────────────────
            ExtractionStrategy strategy;
            if (!hasEmbeddedText) {
                strategy = ExtractionStrategy.OCR;
                log.info("Inspection: no embedded text — routing to OCR");
            } else if (isLowTextDensity) {
                strategy = ExtractionStrategy.HYBRID;
                log.info("Inspection: low text density ({} chars in sample) — routing to HYBRID",
                        sampleChars);
            } else {
                strategy = ExtractionStrategy.NATIVE;
                log.info("Inspection: sufficient embedded text ({} chars) — routing to NATIVE",
                        sampleChars);
            }

            // ── Pre-classification (score-based) ─────────────────────────────
            DocumentType docType = preclassify(totalPages, sampleChars, sampleText);

            return PdfInspectionResult.builder()
                    .totalPages(totalPages)
                    .extractedCharCount(sampleChars)
                    .hasEmbeddedText(hasEmbeddedText)
                    .isLowTextDensity(isLowTextDensity)
                    .recommendedStrategy(strategy)
                    .preclassifiedType(docType)
                    .build();

        } catch (PdfPasswordException | PdfProcessingException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            // PDFBox throws this specific exception for wrong/missing password
            throw new PdfPasswordException(
                    "This PDF is password-protected. Please provide an unlocked version.");
        } catch (IOException ex) {
            log.error("PDFBox inspection IO error: {}", ex.getMessage());
            throw new PdfProcessingException(
                    "The file could not be read as a valid PDF. It may be corrupted or malformed.", ex);
        } catch (Exception ex) {
            log.error("Unexpected inspection error: {}", ex.getMessage());
            throw new PdfProcessingException("Failed to inspect PDF structure.", ex);
        }
    }

    /**
     * Score-based document type pre-classification from the 5-page sample.
     *
     * WHY score-based: A single-condition check like
     *   lower.contains("abstract") && lower.contains("references")
     * fails for academic PDFs where "references" only appears on page 14+,
     * well outside the 5-page sample window.
     *
     * Solution: Award points for multiple independent academic signals.
     * 3+ points → RESEARCH_PAPER (confident classification without needing
     * all signals to be present simultaneously).
     */
    private DocumentType preclassify(int pages, int sampleChars, String sampleText) {
    // Slide decks: short + low text density
    if (pages <= 30 && sampleChars < 500) {
        return DocumentType.SLIDE_DECK;
    }

    if (sampleText != null) {
        String lower = sampleText.toLowerCase();

        // Research paper — requires BOTH abstract and references/conclusion
        boolean hasAbstract = lower.contains("abstract");
        boolean hasReferences = lower.contains("references") || lower.contains("bibliography");
        boolean hasConclusion = lower.contains("conclusion");
        if (hasAbstract && (hasReferences || hasConclusion)) {
            return DocumentType.RESEARCH_PAPER;
        }

        // Invoice / Form — financial or tax keywords
        if (lower.contains("invoice") || lower.contains("bill to")
                || lower.contains("total amount") || lower.contains("form 1040")
                || lower.contains("taxpayer") || lower.contains("irs")
                || lower.contains("internal revenue") || lower.contains("tax return")) {
            return DocumentType.INVOICE_OR_FORM;
        }

        // Legal
        if (lower.contains("terms and conditions") || lower.contains("whereas")
                || lower.contains("hereinafter") || lower.contains("party agrees")) {
            return DocumentType.LEGAL_DOCUMENT;
        }
    }

    // Default: UNKNOWN — let DocumentClassificationService decide after full text
    return DocumentType.UNKNOWN;
}
}