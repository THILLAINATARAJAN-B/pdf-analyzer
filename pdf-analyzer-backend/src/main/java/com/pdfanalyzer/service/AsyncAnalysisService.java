package com.pdfanalyzer.service;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiSafetyException;
import com.pdfanalyzer.exception.PdfPasswordException;
import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.store.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Wraps the synchronous analysis pipeline in a non-blocking async job.
 *
 * Flow:
 * 1. Controller creates a job (returns jobId immediately to the client).
 * 2. This service runs the full pipeline on a separate thread pool.
 * 3. Client polls GET /api/jobs/{id} for status and result.
 *
 * Thread pool is configured in application.properties via:
 * spring.task.execution.pool.core-size=4
 * spring.task.execution.pool.max-size=8
 * spring.task.execution.pool.queue-capacity=50
 *
 * This prevents large/slow PDFs from blocking the HTTP thread
 * and makes the system resilient to slow OCR or AI latency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAnalysisService {

    private final AnalyzeService analyzeService;
    private final JobStore jobStore;

    /**
     * Runs the full analysis pipeline asynchronously.
     * Called AFTER the job has already been created in JobStore.
     *
     * @param jobId   Pre-created job ID to update as processing progresses
     * @param request The original analyze request
     */
    @Async("analysisTaskExecutor")
    public void runAsync(String jobId, AnalyzeRequest request) {
        log.info("Async pipeline started — jobId={}", jobId);

        try {
            jobStore.markProcessing(jobId);
            jobStore.updateProgress(jobId, 15);

            AnalysisResult result = analyzeService.analyze(request);

            jobStore.updateProgress(jobId, 95);
            jobStore.markCompleted(jobId, result);

        } catch (PdfPasswordException ex) {
            jobStore.markFailed(jobId,
                    "This PDF is password-protected. Please provide an unlocked version.");
        } catch (PdfProcessingException ex) {
            jobStore.markFailed(jobId, ex.getMessage());
        } catch (AiSafetyException ex) {
            jobStore.markFailed(jobId,
                    "Document could not be analyzed due to AI safety policy restrictions.");
        } catch (Exception ex) {
            log.error("Async job {} failed with unexpected error [{}]: {}",
                    jobId, ex.getClass().getSimpleName(), ex.getMessage());
            jobStore.markFailed(jobId,
                    "An unexpected error occurred during analysis. Please retry.");
        }
    }
}