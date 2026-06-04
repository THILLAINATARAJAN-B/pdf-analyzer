package com.pdfanalyzer.store;

import com.pdfanalyzer.model.AnalysisJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory job registry using ConcurrentHashMap.
 *
 * Thread-safe for concurrent reads and writes.
 * Jobs are automatically evicted after 30 minutes via scheduled cleanup.
 *
 * For production at scale, replace with Redis:
 * redisTemplate.opsForValue().set("job:" + jobId, job, 30, TimeUnit.MINUTES)
 */
@Slf4j
@Component
public class JobStore {

    private static final int JOB_TTL_MINUTES = 30;
    private final ConcurrentHashMap<String, AnalysisJob> jobs = new ConcurrentHashMap<>();

    public String createJob(String maskedUrl) {
        String jobId = UUID.randomUUID().toString();
        AnalysisJob job = AnalysisJob.builder()
                .jobId(jobId)
                .maskedUrl(maskedUrl)
                .status(AnalysisJob.Status.QUEUED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .progressPercent(0)
                .build();
        jobs.put(jobId, job);
        log.info("Job created: {}", jobId);
        return jobId;
    }

    public Optional<AnalysisJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void markProcessing(String jobId) {
        update(jobId, job -> {
            job.setStatus(AnalysisJob.Status.PROCESSING);
            job.setProgressPercent(10);
            job.setUpdatedAt(Instant.now());
        });
    }

    public void markCompleted(String jobId,
            com.pdfanalyzer.dto.response.AnalysisResult result) {
        update(jobId, job -> {
            job.setStatus(AnalysisJob.Status.COMPLETED);
            job.setResult(result);
            job.setProgressPercent(100);
            job.setUpdatedAt(Instant.now());
        });
        log.info("Job completed: {}", jobId);
    }

    public void markFailed(String jobId, String errorMessage) {
        update(jobId, job -> {
            job.setStatus(AnalysisJob.Status.FAILED);
            job.setErrorMessage(errorMessage);
            job.setProgressPercent(0);
            job.setUpdatedAt(Instant.now());
        });
        log.warn("Job failed: {} — {}", jobId, errorMessage);
    }

    public void updateProgress(String jobId, int percent) {
        update(jobId, job -> {
            job.setProgressPercent(Math.min(percent, 99)); // 100 only on COMPLETED
            job.setUpdatedAt(Instant.now());
        });
    }

    /**
     * Evicts completed/failed jobs older than TTL.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 600_000)
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(JOB_TTL_MINUTES, ChronoUnit.MINUTES);
        int before = jobs.size();
        jobs.entrySet().removeIf(entry -> {
            AnalysisJob job = entry.getValue();
            boolean expired = job.getUpdatedAt().isBefore(cutoff)
                    && (job.getStatus() == AnalysisJob.Status.COMPLETED
                        || job.getStatus() == AnalysisJob.Status.FAILED);
            return expired;
        });
        int evicted = before - jobs.size();
        if (evicted > 0) {
            log.info("Job eviction: removed {} expired jobs. Active: {}", evicted, jobs.size());
        }
    }

    private void update(String jobId, java.util.function.Consumer<AnalysisJob> updater) {
        AnalysisJob job = jobs.get(jobId);
        if (job != null) {
            updater.accept(job);
        } else {
            log.warn("Attempted to update non-existent job: {}", jobId);
        }
    }
}