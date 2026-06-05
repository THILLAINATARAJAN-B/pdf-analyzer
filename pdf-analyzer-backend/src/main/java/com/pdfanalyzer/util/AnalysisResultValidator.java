package com.pdfanalyzer.util;

import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
import org.springframework.stereotype.Component;

@Component
public class AnalysisResultValidator {

    public void validate(AnalysisResult result, String provider) {
        if (result == null) {
            throw new AiServiceException(provider + " returned a null analysis result.");
        }
        if (isBlankOrDefault(result.getTitle()) && isBlankOrDefault(result.getSummary())) {
            throw new AiServiceException(
                    provider + " returned an incomplete analysis — both title and summary are missing.");
        }
    }

    public boolean isBlankOrDefault(String s) {
        return s == null
                || s.isBlank()
                || s.equalsIgnoreCase("N/A")
                || s.equalsIgnoreCase("null")
                || s.equalsIgnoreCase("Not Found");
    }
}