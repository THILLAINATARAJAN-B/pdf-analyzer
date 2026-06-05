package com.pdfanalyzer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    private String documentType;   // ← owned by AI, never overwritten by heuristic
    private String title;
    private String authors;
    private String summary;
    private String keyTakeaway;

    // Pipeline metadata
    private String extractionStrategy;
    private Integer totalPages;
    private String qualityScore;   // HIGH / MEDIUM / LOW — AI-assessed
}