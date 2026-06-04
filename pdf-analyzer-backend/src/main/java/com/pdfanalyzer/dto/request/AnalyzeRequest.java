package com.pdfanalyzer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AnalyzeRequest {

    @NotBlank(message = "PDF URL must not be blank.")
    @Size(max = 2048, message = "URL must not exceed 2048 characters.")
    private String pdfUrl;
}