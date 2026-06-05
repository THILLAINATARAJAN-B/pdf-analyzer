package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.service.AiAnalysisService;
import com.pdfanalyzer.service.AnalyzeService;
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
    private final AiAnalysisService aiAnalysisService;
    // ✂ removed: DocumentClassificationService — AI is the single classification authority

    /**
     * 5-stage document ingestion pipeline.
     *
     * 1. URL Validation  — SSRF protection + DNS pinning
     * 2. Safe Download   — chunked stream, byte limit, magic-byte check
     * 3. PDF Inspection  — structure analysis + extraction strategy + soft type hint
     * 4. Text Extraction — NATIVE / OCR / HYBRID routing
     * 5. AI Analysis     — single classification authority + structured output with retry
     */
    @Override
    public AnalysisResult analyze(AnalyzeRequest request) {
        String pdfUrl = request.getPdfUrl().trim();
        log.info("=== PDF Analysis Pipeline START ===");

        // Stage 1 — URL Validation
        log.info("[Stage 1] Validating URL");
        urlValidator.validate(pdfUrl);

        // Stage 2 — Safe Chunked Download
        log.info("[Stage 2] Downloading PDF");
        byte[] pdfBytes = pdfDownloadService.download(pdfUrl);

        // Stage 3 — PDF Structural Inspection
        log.info("[Stage 3] Inspecting PDF structure");
        PdfInspectionResult inspection = pdfInspectionService.inspect(pdfBytes);
        log.info("[Stage 3] pages={}, strategy={}, structuralHint={}",
                inspection.getTotalPages(),
                inspection.getRecommendedStrategy(),
                inspection.getPreclassifiedType());

        // Stage 4 — Text Extraction
        log.info("[Stage 4] Extracting text — strategy: {}",
                inspection.getRecommendedStrategy());
        String extractedText = extractionOrchestrator.extract(pdfBytes, inspection);
        log.info("[Stage 4] Extracted {} chars", extractedText.length());

        // Stage 5 — AI Analysis (sole classification authority)
        log.info("[Stage 5] Sending to AI — structural hint: {}",
                inspection.getPreclassifiedType());
        AnalysisResult result = aiAnalysisService.analyze(
                extractedText,
                inspection.getPreclassifiedType().name()   // ← raw enum name, hint only
        );

        result.setExtractionStrategy(inspection.getRecommendedStrategy().name());
        result.setTotalPages(inspection.getTotalPages());

        log.info("=== PDF Analysis Pipeline COMPLETE === strategy={}, pages={}",
                inspection.getRecommendedStrategy(), inspection.getTotalPages());

        return result;
    }
}