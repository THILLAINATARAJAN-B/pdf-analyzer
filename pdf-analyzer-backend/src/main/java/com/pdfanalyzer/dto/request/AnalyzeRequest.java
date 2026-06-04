package com.pdfanalyzer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeRequest {

    @NotBlank(message = "PDF URL must not be blank")
    @Size(max = 2048, message = "PDF URL must not exceed 2048 characters")
    private String pdfUrl;
}