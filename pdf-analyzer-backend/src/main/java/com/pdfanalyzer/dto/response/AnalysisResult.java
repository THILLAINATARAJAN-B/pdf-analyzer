package com.pdfanalyzer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The five fields the assignment requires, nothing more.
 * extractionStrategy and totalPages are kept as lightweight
 * pipeline metadata — they add value in interview review
 * without bloating the contract.
 */
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

    // Lightweight pipeline metadata — shown in response, useful for interview
    private String extractionStrategy;
    private Integer totalPages;
}