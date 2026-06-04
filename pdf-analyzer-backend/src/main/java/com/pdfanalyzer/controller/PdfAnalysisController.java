package com.pdfanalyzer.controller;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.service.AnalyzeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/analyze")
@RequiredArgsConstructor
public class PdfAnalysisController {

    private final AnalyzeService analyzeService;

    @PostMapping
    public ResponseEntity<AnalysisResult> analyze(
            @Valid @RequestBody AnalyzeRequest request) {

        log.info("Analysis request received for URL: {}",
                maskUrl(request.getPdfUrl()));

        AnalysisResult result = analyzeService.analyze(request);

        log.info("Analysis completed successfully. Strategy: {}, Pages: {}",
                result.getExtractionStrategy(), result.getTotalPages());

        return ResponseEntity.ok(result);
    }

    /**
     * Masks the URL for safe logging — hides query params and credentials.
     */
    private String maskUrl(String url) {
        if (url == null) return "null";
        int qIndex = url.indexOf('?');
        return qIndex > 0 ? url.substring(0, qIndex) + "?[params-hidden]" : url;
    }
}