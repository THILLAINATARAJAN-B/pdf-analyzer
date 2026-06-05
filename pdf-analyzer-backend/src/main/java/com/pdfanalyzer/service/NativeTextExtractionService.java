package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Extracts embedded text from a PDF using PDFBox.
 *
 * Uses setSortByPosition(true) for proper layout-aware extraction,
 * which significantly improves results for multi-column academic papers
 * and slide-style PDFs before OCR is considered.
 *
 * Applies smart sampling for large documents:
 * - Short docs (≤4 pages): full extraction
 * - Long docs: first 3 + last 2 pages (title/abstract + conclusion)
 */
@Slf4j
@Service
public class NativeTextExtractionService {

    @Value("${pdf.processing.max-text-chars:40000}")
    private int maxTextChars;
    private static final int HEADER_PAGES = 5;

    public String extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            log.info("Native extraction — {} pages", totalPages);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Critical for multi-column and slide PDFs

            String text;
            if (totalPages <= 4) {
                stripper.setStartPage(1);
                stripper.setEndPage(totalPages);
                text = stripper.getText(document);
            } else {
                text = smartSample(document, stripper, totalPages);
            }

            return normalizeAndTruncate(text);

        } catch (InvalidPasswordException ex) {
            throw new com.pdfanalyzer.exception.PdfPasswordException(
                    "This PDF is password-protected. Please provide an unlocked version.");
        } catch (IOException ex) {
            log.error("Native extraction IO error: {}", ex.getMessage());
            throw new PdfProcessingException(
                    "Failed to read PDF during text extraction. The file may be corrupted.", ex);
        }
    }

    private String smartSample(PDDocument document, PDFTextStripper stripper,
                        int totalPages) throws IOException {

    int footerStart = totalPages - 2 + 1;

    if (footerStart <= HEADER_PAGES + 1) {
        log.info("Smart sampling — short doc ({} pages), extracting fully", totalPages);
        stripper.setStartPage(1);
        stripper.setEndPage(totalPages);
        return stripper.getText(document);
    }

    log.info("Smart sampling — extracting first {} + last 2 pages from {} total",
            HEADER_PAGES, totalPages);

    // ── Page 1: extract TWICE — once sorted (layout-aware) and once unsorted
    //    (reading-order). Prepend both so the AI sees author names regardless
    //    of how PDFBox handles the multi-column header grid.
    stripper.setSortByPosition(false);   // reading order — better for author grids
    stripper.setStartPage(1);
    stripper.setEndPage(1);
    String page1ReadingOrder = stripper.getText(document);

    stripper.setSortByPosition(true);    // layout order — better for body text
    stripper.setStartPage(1);
    stripper.setEndPage(HEADER_PAGES);
    String header = stripper.getText(document);

    stripper.setStartPage(footerStart);
    stripper.setEndPage(totalPages);
    String footer = stripper.getText(document);

    int omittedStart = HEADER_PAGES + 1;
    int omittedEnd   = footerStart - 1;

    // Prepend reading-order page 1 so AI sees all author names in natural flow
    return "[PAGE 1 - READING ORDER FOR AUTHOR EXTRACTION]\n"
            + page1ReadingOrder
            + "\n[PAGE 1-" + HEADER_PAGES + " - LAYOUT ORDER]\n"
            + header
            + "\n\n[--- Pages " + omittedStart + " to " + omittedEnd
            + " omitted for token efficiency ---]\n\n"
            + footer;
}

    private String normalizeAndTruncate(String raw) {
        if (raw == null || raw.isBlank()) return "";

        // Normalize excessive whitespace while preserving paragraph breaks
        String normalized = raw
                .replaceAll("[ \\t]+", " ")            // collapse horizontal whitespace
                .replaceAll("\\n{3,}", "\n\n")          // collapse excessive newlines
                .trim();

        if (normalized.length() > maxTextChars) {
            log.info("Text truncated: {} → {} chars", normalized.length(), maxTextChars);
            normalized = normalized.substring(0, maxTextChars)
                    + "\n\n[Content truncated — document too large for full analysis]";
        }

        return normalized;
    }
}