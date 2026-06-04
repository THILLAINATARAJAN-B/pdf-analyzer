package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Routes extraction to the correct service based on the
 * strategy recommended by PdfInspectionService.
 *
 * Strategy routing:
 * - NATIVE  → NativeTextExtractionService only
 * - OCR     → OcrExtractionService only
 * - HYBRID  → Native first, then supplement with OCR if text is insufficient
 *
 * OCR is only attempted if pdf.processing.ocr-enabled=true.
 * If OCR is disabled and the strategy requires it,
 * a descriptive 422 error is returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExtractionOrchestrator {

    private final NativeTextExtractionService nativeExtractor;
    private final OcrExtractionService ocrExtractor;

    @Value("${pdf.processing.ocr-enabled:true}")
    private boolean ocrEnabled;

    @Value("${pdf.processing.low-text-threshold:100}")
    private int lowTextThreshold;

    public String extract(byte[] pdfBytes, PdfInspectionResult inspection) {
        ExtractionStrategy strategy = inspection.getRecommendedStrategy();
        log.info("Extraction strategy: {}", strategy);

        return switch (strategy) {
            case NATIVE -> extractNative(pdfBytes);
            case OCR    -> extractOcr(pdfBytes, strategy);
            case HYBRID -> extractHybrid(pdfBytes);
        };
    }

    private String extractNative(byte[] pdfBytes) {
        String text = nativeExtractor.extract(pdfBytes);
        if (text == null || text.isBlank()) {
            if (ocrEnabled && ocrExtractor.isAvailable()) {
                log.info("Native extraction empty — attempting OCR recovery");
                return ocrExtractor.extractWithOcr(pdfBytes);
            }
            throw new PdfProcessingException(
                    "No readable text could be extracted from this PDF. "
                            + "It appears to be fully image-based (scanned). "
                            + (ocrExtractor.isAvailable()
                            ? "OCR was attempted but yielded no text."
                            : "OCR is not available on this server."));
        }
        return text;
    }

    private String extractOcr(byte[] pdfBytes, ExtractionStrategy strategy) {
        if (!ocrEnabled) {
            throw new PdfProcessingException(
                    "This PDF appears to be scanned or image-based and requires OCR, "
                            + "which is not currently enabled on this server.");
        }
        log.info("Routing to OCR extraction (strategy={})", strategy);
        return ocrExtractor.extractWithOcr(pdfBytes);
    }

    private String extractHybrid(byte[] pdfBytes) {
        log.info("Hybrid extraction: attempting native first");
        String nativeText = nativeExtractor.extract(pdfBytes);

        boolean nativeInsufficient = nativeText == null
                || nativeText.trim().length() < lowTextThreshold;

        if (!nativeInsufficient) {
            log.info("Hybrid: native text sufficient ({} chars)", nativeText.length());
            return nativeText;
        }

        log.info("Hybrid: native text insufficient ({} chars) — falling back to OCR",
                nativeText == null ? 0 : nativeText.trim().length());

        if (!ocrEnabled || !ocrExtractor.isAvailable()) {
            if (nativeText != null && !nativeText.isBlank()) {
                log.warn("Hybrid fallback: OCR unavailable — returning partial native text");
                return nativeText;
            }
            throw new PdfProcessingException(
                    "This PDF has very little embedded text and requires OCR for analysis, "
                            + "but Tesseract OCR is not available on this server.");
        }

        try {
            String ocrText = ocrExtractor.extractWithOcr(pdfBytes);

            if (ocrText.length() > (nativeText == null ? 0 : nativeText.length()) * 2) {
                log.info("Hybrid: OCR dominant ({} chars vs native {} chars)",
                        ocrText.length(), nativeText == null ? 0 : nativeText.length());
                return ocrText;
            }

            String merged = ((nativeText == null ? "" : nativeText) + "\n\n[OCR Supplement]\n\n" + ocrText).trim();
            log.info("Hybrid: merged result {} chars", merged.length());
            return merged;

        } catch (PdfProcessingException ex) {
            if (nativeText != null && !nativeText.isBlank()) {
                log.warn("Hybrid OCR failed — returning partial native text: {}", ex.getMessage());
                return nativeText;
            }
            throw ex;
        }
    }
}