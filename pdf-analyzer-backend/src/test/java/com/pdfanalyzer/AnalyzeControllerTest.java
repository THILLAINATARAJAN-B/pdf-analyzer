package com.pdfanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.controller.AnalyzeController;
import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.service.AnalyzeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AnalyzeService analyzeService;

    @Test
    @DisplayName("POST /api/v1/analyze - returns success with analysis result")
    void testAnalyzeSuccess() throws Exception {
        AnalysisResult mockResult = AnalysisResult.builder()
                .documentType("Research Paper")
                .title("Attention Is All You Need")
                .authors("Vaswani et al.")
                .summary("This paper introduces the Transformer architecture.")
                .keyTakeaway("Self-attention mechanisms can replace RNNs in NLP tasks.")
                .build();

        when(analyzeService.analyze(any())).thenReturn(mockResult);

        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("https://arxiv.org/pdf/1706.03762");

        mockMvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Attention Is All You Need"))
                .andExpect(jsonPath("$.data.documentType").value("Research Paper"));
    }

    @Test
    @DisplayName("POST /api/v1/analyze - returns 400 for blank URL")
    void testAnalyzeBlankUrl() throws Exception {
        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("");

        mockMvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pdfUrl: PDF URL must not be blank."));
    }

    @Test
    @DisplayName("GET /api/v1/health - returns health check")
    void testHealth() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}