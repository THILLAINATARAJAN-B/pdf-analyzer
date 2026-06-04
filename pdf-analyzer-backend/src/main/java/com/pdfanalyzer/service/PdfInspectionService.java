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
 * This service is deliberately read-only — it does not
 * extract full text, only enough to detect text density.
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

            // Password-protected PDF — explicit exception with clean message
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
            PDFTextStripper sampleStripper = new PDFTextStripper();
            sampleStripper.setSortByPosition(true);
            sampleStripper.setStartPage(1);
            sampleStripper.setEndPage(Math.min(5, totalPages));
            String sampleText = sampleStripper.getText(document);
            int sampleChars = sampleText == null ? 0 : sampleText.trim().length();

            boolean hasEmbeddedText = sampleChars > 0;
            boolean isLowTextDensity = sampleChars < lowTextThreshold;

            // Determine extraction strategy
            ExtractionStrategy strategy;
            if (!hasEmbeddedText) {
                strategy = ExtractionStrategy.OCR;
                log.info("Inspection: no embedded text detected — routing to OCR");
            } else if (isLowTextDensity) {
                strategy = ExtractionStrategy.HYBRID;
                log.info("Inspection: low text density ({} chars in sample) — routing to HYBRID", sampleChars);
            } else {
                strategy = ExtractionStrategy.NATIVE;
                log.info("Inspection: sufficient embedded text ({} chars) — routing to NATIVE", sampleChars);
            }

            // Pre-classify document type from structural signals
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
     * Pre-classifies document type from structural signals:
     * - Slide decks tend to be short with low text density per page.
     * - Research papers tend to have dense text.
     * - All other cases default to UNKNOWN for the AI to determine.
     */
    private DocumentType preclassify(int pages, int sampleChars, String sampleText) {
        if (pages <= 30 && sampleChars < 500) {
            return DocumentType.SLIDE_DECK;
        }
        if (sampleText != null) {
            String lower = sampleText.toLowerCase();
            if (lower.contains("abstract") && lower.contains("references")) {
                return DocumentType.RESEARCH_PAPER;
            }
            if (lower.contains("invoice") || lower.contains("bill to") || lower.contains("total amount")) {
                return DocumentType.INVOICE_OR_FORM;
            }
            if (lower.contains("terms and conditions") || lower.contains("whereas") || lower.contains("hereinafter")) {
                return DocumentType.LEGAL_DOCUMENT;
            }
        }
        return DocumentType.UNKNOWN;
    }
}