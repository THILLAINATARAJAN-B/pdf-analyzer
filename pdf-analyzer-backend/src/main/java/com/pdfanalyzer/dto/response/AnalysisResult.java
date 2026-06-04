package com.pdfanalyzer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResult {

    private String documentType;
    private String title;
    private String authors;
    private String summary;
    private String keyTakeaway;

    // Pipeline metadata — returned to client for transparency
    private String extractionStrategy;
    private Integer totalPages;
    private String qualityScore;
}