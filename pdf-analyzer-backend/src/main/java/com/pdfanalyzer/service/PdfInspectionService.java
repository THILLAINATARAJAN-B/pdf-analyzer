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

@Slf4j
@Service
public class PdfInspectionService {

    @Value("${pdf.processing.max-pages:200}")
    private int maxPages;

    @Value("${pdf.processing.low-text-threshold:100}")
    private int lowTextThreshold;

    public PdfInspectionResult inspect(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

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

            // Sample first 5 pages for extraction strategy decision
            PDFTextStripper sampleStripper = new PDFTextStripper();
            sampleStripper.setSortByPosition(true);
            sampleStripper.setStartPage(1);
            sampleStripper.setEndPage(Math.min(5, totalPages));
            String sampleText = sampleStripper.getText(document);
            int sampleChars = (sampleText == null) ? 0 : sampleText.trim().length();

            // Sample wider range (up to page 10) for pre-classification hinting.
            // Keeps extraction strategy fast while giving the AI a better structural hint.
            PDFTextStripper classifyStripper = new PDFTextStripper();
            classifyStripper.setSortByPosition(true);
            classifyStripper.setStartPage(1);
            classifyStripper.setEndPage(Math.min(10, totalPages));
            String classifySample = classifyStripper.getText(document);

            boolean hasEmbeddedText = sampleChars > 0;
            boolean isLowTextDensity = sampleChars < lowTextThreshold;

            ExtractionStrategy strategy;
            if (!hasEmbeddedText) {
                strategy = ExtractionStrategy.OCR;
                log.info("Inspection: no embedded text — routing to OCR");
            } else if (isLowTextDensity) {
                strategy = ExtractionStrategy.HYBRID;
                log.info("Inspection: low text density ({} chars) — routing to HYBRID", sampleChars);
            } else {
                strategy = ExtractionStrategy.NATIVE;
                log.info("Inspection: sufficient embedded text ({} chars) — routing to NATIVE", sampleChars);
            }

            DocumentType docType = preclassify(totalPages, sampleChars, classifySample);

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
            throw new PdfPasswordException(
                    "This PDF is password-protected. Please provide an unlocked version.");
        } catch (IOException ex) {
            log.error("PDFBox inspection IO error: {}", ex.getMessage());
            throw new PdfProcessingException(
                    "The file could not be read as a valid PDF. It may be corrupted.", ex);
        } catch (Exception ex) {
            log.error("Unexpected inspection error: {}", ex.getMessage());
            throw new PdfProcessingException("Failed to inspect PDF structure.", ex);
        }
    }

    /**
     * Score-based pre-classification from the wider 10-page sample.
     *
     * Priority order:
     * 1. Research Paper — requires abstract + (references OR conclusion)
     *    Both checks use the wider sample to avoid missing signals outside the first few pages.
     * 2. Government Document — IRS/tax publication signals
     * 3. Legal — boilerplate legal phrases
     * 4. Invoice/Form — STRICT: page-count guard (≤25) prevents a long IRS
     *    publication from being classified as a form just because it mentions "form 1040"
     * 5. Slide Deck — structural: short + low text density
     * 6. UNKNOWN — let the AI determine the final document type from full content
     */
    private DocumentType preclassify(int pages, int sampleChars, String sampleText) {
        if (sampleText == null || sampleText.isBlank()) {
            return DocumentType.UNKNOWN;
        }

        String lower = sampleText.toLowerCase();

        boolean hasAbstract = lower.contains("abstract");
        boolean hasReferences = lower.contains("references") || lower.contains("bibliography");
        boolean hasConclusion = lower.contains("conclusion");
        if (hasAbstract && (hasReferences || hasConclusion)) {
            log.info("Pre-classification: RESEARCH_PAPER");
            return DocumentType.RESEARCH_PAPER;
        }

        if (lower.contains("internal revenue service")
                || lower.contains("department of the treasury")
                || lower.contains("publication") && (lower.contains("irs") || lower.contains("taxpayer"))) {
            log.info("Pre-classification: GOVERNMENT_DOCUMENT");
            return DocumentType.GOVERNMENT_DOCUMENT;
        }

        if (lower.contains("terms and conditions") || lower.contains("whereas")
                || lower.contains("hereinafter") || lower.contains("party agrees")) {
            log.info("Pre-classification: LEGAL_DOCUMENT");
            return DocumentType.LEGAL_DOCUMENT;
        }

        if (pages <= 25 && (lower.contains("invoice") || lower.contains("bill to")
                || lower.contains("total amount") || lower.contains("purchase order"))) {
            log.info("Pre-classification: INVOICE_OR_FORM");
            return DocumentType.INVOICE_OR_FORM;
        }

        if (pages <= 30 && sampleChars < 500) {
            log.info("Pre-classification: SLIDE_DECK");
            return DocumentType.SLIDE_DECK;
        }

        log.info("Pre-classification: UNKNOWN — letting AI determine final document type");
        return DocumentType.UNKNOWN;
    }
}