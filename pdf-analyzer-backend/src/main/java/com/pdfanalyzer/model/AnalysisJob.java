package com.pdfanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pdfanalyzer.dto.response.AnalysisResult;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Represents the lifecycle state of an async analysis job.
 * Stored in JobStore (in-memory) keyed by jobId (UUID).
 */
@Getter
@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisJob {

    public enum Status {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    private final String jobId;
    private final String maskedUrl;
    private final Instant createdAt;

    private volatile Status status;
    private volatile Instant updatedAt;

    /** Populated when status = COMPLETED */
    private volatile AnalysisResult result;

    /** Populated when status = FAILED */
    private volatile String errorMessage;

    /** Estimated progress percentage (0–100) */
    private volatile int progressPercent;
}