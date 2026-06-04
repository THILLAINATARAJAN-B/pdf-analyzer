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
        // Structural signal: very short with sparse text → slide deck
        if (pages <= 30 && sampleChars < 500) {
            log.debug("Pre-classification: SLIDE_DECK (pages={}, sampleChars={})",
                    pages, sampleChars);
            return DocumentType.SLIDE_DECK;
        }

        if (sampleText == null || sampleText.isBlank()) {
            return DocumentType.UNKNOWN;
        }

        String lower = sampleText.toLowerCase();

        // ── Score-based research paper detection ──────────────────────────────
        // These signals appear in the first 5 pages of virtually all academic PDFs.
        // References/bibliography are intentionally excluded from this sample check
        // since they appear at the end of the document.
        boolean hasAbstract = lower.contains("abstract");
        boolean hasIntro    = lower.contains("introduction");
        boolean hasDoi      = lower.contains("doi:") || lower.contains("arxiv")
                           || lower.contains("arxiv.org");
        boolean hasEtAl     = lower.contains("et al.");
        boolean hasFigure   = lower.contains("figure") || lower.contains("fig.");
        boolean hasSection  = lower.contains("section");
        boolean hasKeywords = lower.contains("keywords") || lower.contains("key words");

        int researchScore = (hasAbstract ? 2 : 0)
                          + (hasIntro    ? 1 : 0)
                          + (hasDoi      ? 2 : 0)
                          + (hasEtAl     ? 2 : 0)
                          + (hasFigure   ? 1 : 0)
                          + (hasSection  ? 1 : 0)
                          + (hasKeywords ? 1 : 0);

        if (researchScore >= 3) {
            log.debug("Pre-classification: RESEARCH_PAPER (score={})", researchScore);
            return DocumentType.RESEARCH_PAPER;
        }

        // ── Other document types ──────────────────────────────────────────────
        if (lower.contains("invoice") || lower.contains("bill to")
                || lower.contains("total amount") || lower.contains("tax invoice")) {
            return DocumentType.INVOICE_OR_FORM;
        }
        if (lower.contains("terms and conditions") || lower.contains("whereas")
                || lower.contains("hereinafter") || lower.contains("party agrees")) {
            return DocumentType.LEGAL_DOCUMENT;
        }

        return DocumentType.UNKNOWN;
    }
}