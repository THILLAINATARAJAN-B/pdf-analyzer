package com.pdfanalyzer.controller;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.dto.response.ApiResponse;
import com.pdfanalyzer.service.AnalyzeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalyzeController {

    private final AnalyzeService analyzeService;

    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<AnalysisResult>> analyze(
            @Valid @RequestBody AnalyzeRequest request) {
        log.info("Received analyze request for URL: {}", maskUrl(request.getPdfUrl()));
        AnalysisResult result = analyzeService.analyze(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("PDF Analyzer API is running"));
    }

    /**
     * Masks the URL for safe logging — avoids leaking user-submitted URLs in full.
     */
    private String maskUrl(String url) {
        if (url == null || url.length() < 20) return "***";
        return url.substring(0, 20) + "...";
    }
}