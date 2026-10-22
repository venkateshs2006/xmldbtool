package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.model.JobTelemetryResponse;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobTelemetryController {

    private final DynamicBatchJobService batchJobService;

    public JobTelemetryController(DynamicBatchJobService batchJobService) {
        this.batchJobService = batchJobService;
    }

    @PostMapping("/launch")
    public ResponseEntity<JobLaunchResponse> launchJob(@RequestBody JobLaunchRequest request) {
        JobLaunchResponse response = batchJobService.launchIngestionJob(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/{jobExecutionId}/telemetry")
    public ResponseEntity<JobTelemetryResponse> getJobTelemetry(@PathVariable("jobExecutionId") Long jobExecutionId) {
        JobTelemetryResponse telemetry = batchJobService.getJobTelemetry(jobExecutionId);
        return ResponseEntity.ok(telemetry);
    }

    @PostMapping("/{jobExecutionId}/stop")
    public ResponseEntity<Map<String, Object>> stopJob(@PathVariable("jobExecutionId") Long jobExecutionId) {
        boolean stopped = batchJobService.stopJob(jobExecutionId);
        return ResponseEntity.ok(Map.of(
                "jobExecutionId", jobExecutionId,
                "stopped", stopped,
                "message", stopped ? "Termination signal acknowledged." : "Job was not actively running."
        ));
    }
}
