package com.pdfanalyzer.util;

import com.pdfanalyzer.exception.PdfProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Slf4j
@Component
public class PdfTextExtractor {

    @Value("${pdf.download.max-text-chars}")
    private int maxTextChars;

    public String extract(byte[] pdfBytes) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {

            if (document.isEncrypted()) {
                throw new PdfProcessingException(
                        "The PDF is encrypted or password-protected and cannot be analyzed.");
            }

            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                throw new PdfProcessingException("The PDF appears to have no pages.");
            }

            log.info("Extracting text from PDF with {} pages", totalPages);

            String extractedText = smartExtract(document, totalPages);

            if (extractedText == null || extractedText.isBlank()) {
                throw new PdfProcessingException(
                        "No readable text could be extracted from this PDF. "
                                + "It may be an image-only or scanned PDF.");
            }

            // Normalize excessive whitespace
            extractedText = extractedText.replaceAll("\\s{3,}", "\n\n").trim();

            // Truncate to safe limit
            if (extractedText.length() > maxTextChars) {
                log.info("Text truncated from {} to {} chars",
                        extractedText.length(), maxTextChars);
                extractedText = extractedText.substring(0, maxTextChars)
                        + "\n\n[Content truncated for analysis]";
            }

            return extractedText;

        } catch (PdfProcessingException e) {
            throw e;
        } catch (IOException e) {
            log.error("PDFBox IO error: {}", e.getMessage());
            throw new PdfProcessingException(
                    "The file could not be read as a valid PDF. It may be corrupted.");
        } catch (Exception e) {
            log.error("Unexpected PDF extraction error: {}", e.getMessage());
            throw new PdfProcessingException("Failed to extract text from the PDF.");
        }
    }

    /**
     * Smart sampling strategy:
     * - Short docs (<=4 pages): extract everything.
     * - Long docs: extract first 3 pages (title, authors, abstract)
     *   + last 2 pages (conclusion, key takeaways).
     * This keeps token count low while preserving the most analytically
     * valuable sections for the AI, avoiding 429 rate limit errors.
     */
    private String smartExtract(PDDocument document, int totalPages) throws IOException {

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        if (totalPages <= 4) {
            // Short document — extract all pages
            log.info("Short PDF ({} pages) — extracting all pages", totalPages);
            stripper.setStartPage(1);
            stripper.setEndPage(totalPages);
            return stripper.getText(document);
        }

        // Long document — smart sampling
        log.info("Long PDF ({} pages) — applying smart sampling (first 3 + last 2 pages)",
                totalPages);

        // First 3 pages: title, authors, abstract, introduction
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(3, totalPages));
        String headerText = stripper.getText(document);

        // Last 2 pages: conclusion, key takeaways, references summary
        int lastStart = Math.max(totalPages - 1, 4);
        stripper.setStartPage(lastStart);
        stripper.setEndPage(totalPages);
        String footerText = stripper.getText(document);

        return headerText
                + "\n\n[... middle sections omitted for token efficiency ...]\n\n"
                + footerText;
    }
}