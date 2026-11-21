package com.bnpp.controller;

import com.bnpp.model.*;
import com.bnpp.service.AuditGovernanceService;
import com.bnpp.service.BatchPipelineJobService;
import com.bnpp.service.SystemMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class JobTelemetryController {

    private final BatchPipelineJobService batchPipelineJobService;
    private final SystemMetricsService systemMetricsService;
    private final AuditGovernanceService auditGovernanceService;

    public JobTelemetryController(BatchPipelineJobService batchPipelineJobService,
                                  SystemMetricsService systemMetricsService,
                                  AuditGovernanceService auditGovernanceService) {
        this.batchPipelineJobService = batchPipelineJobService;
        this.systemMetricsService = systemMetricsService;
        this.auditGovernanceService = auditGovernanceService;
    }

    @GetMapping("/api/system/metrics")
    public ResponseEntity<SystemMetrics> getSystemMetrics() {
        return ResponseEntity.ok(systemMetricsService.getSystemMetrics());
    }

    @GetMapping("/api/jobs/history")
    public ResponseEntity<List<JobLaunchRequest>> getJobHistory() {
        return ResponseEntity.ok(batchPipelineJobService.getAllJobs());
    }

    @GetMapping("/api/jobs/{jobExecutionId}/telemetry")
    public ResponseEntity<JobTelemetryResponse> getJobTelemetry(@PathVariable String jobExecutionId) {
        JobTelemetryResponse telem = batchPipelineJobService.getTelemetry(jobExecutionId);
        SystemMetrics sys = systemMetricsService.getSystemMetrics();
        if (telem != null) {
            telem.setCpuUsage(sys.getCpuUsage());
            telem.setMemoryUsage(sys.getMemoryUsage());
            telem.setCpuCores(sys.getAvailableProcessors());
        }
        return ResponseEntity.ok(telem);
    }

    @PostMapping("/api/jobs/{jobExecutionId}/stop")
    public ResponseEntity<?> stopJob(@PathVariable String jobExecutionId) {
        boolean stopped = batchPipelineJobService.stopJob(jobExecutionId);
        return ResponseEntity.ok(Map.of("success", stopped, "message", "Stop signal dispatched for execution " + jobExecutionId));
    }

    @PostMapping("/api/jobs/{jobExecutionId}/resume")
    public ResponseEntity<?> resumeJob(@PathVariable String jobExecutionId) {
        try {
            JobLaunchResponse resp = batchPipelineJobService.resumeJob(jobExecutionId);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/jobs/{jobExecutionId}/retry")
    public ResponseEntity<?> retryJob(@PathVariable String jobExecutionId) {
        try {
            JobLaunchResponse resp = batchPipelineJobService.retryJob(jobExecutionId);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @GetMapping("/api/jobs/file-history")
    public ResponseEntity<List<FileHistoryRecord>> getFileHistory(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        // Find most recent database configuration from submitted jobs
        List<JobLaunchRequest> jobs = batchPipelineJobService.getAllJobs();
        DbConnectionConfig config = null;
        for (JobLaunchRequest j : jobs) {
            if (j.getDbConfig() != null) {
                config = j.getDbConfig();
                break;
            }
        }
        if (config != null) {
            return ResponseEntity.ok(auditGovernanceService.listFileHistory(config, limit));
        }
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/api/jobs/audit-log")
    public ResponseEntity<List<AuditLogRecord>> getAuditLogs(@RequestParam(value = "jobExecutionId", required = false) String execId,
                                                             @RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<JobLaunchRequest> jobs = batchPipelineJobService.getAllJobs();
        DbConnectionConfig config = null;
        for (JobLaunchRequest j : jobs) {
            if (j.getDbConfig() != null) {
                config = j.getDbConfig();
                break;
            }
        }
        if (config != null) {
            return ResponseEntity.ok(auditGovernanceService.listAuditLogs(config, execId, limit));
        }
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/api/jobs/error-log")
    public ResponseEntity<List<ErrorLogRecord>> getErrorLogs(@RequestParam(value = "jobExecutionId", required = false) String execId,
                                                             @RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<JobLaunchRequest> jobs = batchPipelineJobService.getAllJobs();
        DbConnectionConfig config = null;
        for (JobLaunchRequest j : jobs) {
            if (j.getDbConfig() != null) {
                config = j.getDbConfig();
                break;
            }
        }
        if (config != null) {
            return ResponseEntity.ok(auditGovernanceService.listErrorLogs(config, execId, limit));
        }
        return ResponseEntity.ok(Collections.emptyList());
    }
}
