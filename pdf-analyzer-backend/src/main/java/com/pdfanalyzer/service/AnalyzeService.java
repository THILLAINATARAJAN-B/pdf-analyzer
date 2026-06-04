package com.pdfanalyzer.service;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;

public interface AnalyzeService {
    AnalysisResult analyze(AnalyzeRequest request);
}