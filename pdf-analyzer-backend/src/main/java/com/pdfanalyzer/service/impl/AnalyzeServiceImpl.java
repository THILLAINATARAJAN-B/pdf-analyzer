package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.service.AiAnalysisService;
import com.pdfanalyzer.service.AnalyzeService;
import com.pdfanalyzer.service.DocumentClassificationService;
import com.pdfanalyzer.service.PdfDownloadService;
import com.pdfanalyzer.service.PdfExtractionOrchestrator;
import com.pdfanalyzer.service.PdfInspectionService;
import com.pdfanalyzer.validation.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeServiceImpl implements AnalyzeService {

    private final UrlValidator urlValidator;
    private final PdfDownloadService pdfDownloadService;
    private final PdfInspectionService pdfInspectionService;
    private final PdfExtractionOrchestrator extractionOrchestrator;
    private final DocumentClassificationService classificationService;
    private final AiAnalysisService aiAnalysisService;

    /**
     * Full 6-stage document ingestion pipeline:
     *
     * 1. URL Validation (SSRF protection + DNS pinning)
     * 2. Safe chunked download (size-limited, magic-byte checked)
     * 3. PDF Inspection (structure analysis, page count, text density)
     * 4. Extraction Strategy Decision (NATIVE / OCR / HYBRID)
     * 5. Pre-classification (document type signals for prompt enrichment)
     * 6. Gemini Structured Analysis (with retry + fallback)
     */
    @Override
    public AnalysisResult analyze(AnalyzeRequest request) {
        String pdfUrl = request.getPdfUrl().trim();
        log.info("=== PDF Analysis Pipeline START ===");

        // Stage 1 — URL Validation
        log.info("[Stage 1] Validating URL");
        urlValidator.validate(pdfUrl);

        // Stage 2 — Safe Download
        log.info("[Stage 2] Downloading PDF");
        byte[] pdfBytes = pdfDownloadService.download(pdfUrl);

        // Stage 3 — PDF Structural Inspection
        log.info("[Stage 3] Inspecting PDF structure");
        PdfInspectionResult inspection = pdfInspectionService.inspect(pdfBytes);
        log.info("[Stage 3] Inspection complete — pages={}, strategy={}, type={}",
                inspection.getTotalPages(),
                inspection.getRecommendedStrategy(),
                inspection.getPreclassifiedType());

        // Stage 4 — Text Extraction via Strategy
        log.info("[Stage 4] Extracting text using strategy: {}",
                inspection.getRecommendedStrategy());
        String extractedText = extractionOrchestrator.extract(pdfBytes, inspection);
        log.info("[Stage 4] Extracted {} chars", extractedText.length());

        // Stage 5 — Document Pre-classification (enriches AI prompt)
        log.info("[Stage 5] Pre-classifying document");
        String documentTypeHint = classificationService.classify(extractedText, inspection);
        log.info("[Stage 5] Pre-classified as: {}", documentTypeHint);

        // Stage 6 — Gemini Structured Analysis
        log.info("[Stage 6] Sending to AI analysis service");
        AnalysisResult result = aiAnalysisService.analyze(extractedText, documentTypeHint);

        // Enrich result with pipeline metadata
        result.setExtractionStrategy(inspection.getRecommendedStrategy().name());
        result.setTotalPages(inspection.getTotalPages());
        result.setQualityScore(computeQualityScore(inspection, extractedText));

        log.info("=== PDF Analysis Pipeline COMPLETE ===");
        return result;
    }

    private String computeQualityScore(PdfInspectionResult inspection, String text) {
        int chars = text.length();
        if (chars > 5000 && inspection.isNativeExtractionReliable()) return "HIGH";
        if (chars > 1000) return "MEDIUM";
        return "LOW";
    }
}