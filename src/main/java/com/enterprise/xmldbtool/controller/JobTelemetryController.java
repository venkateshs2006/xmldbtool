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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobTelemetryController {

    private final DynamicBatchJobService batchJobService;
    private final com.enterprise.xmldbtool.service.UserService userService;

    public JobTelemetryController(DynamicBatchJobService batchJobService, com.enterprise.xmldbtool.service.UserService userService) {
        this.batchJobService = batchJobService;
        this.userService = userService;
    }

    @PostMapping("/launch")
    public ResponseEntity<?> launchJob(@RequestBody JobLaunchRequest request, @RequestHeader(value = "Authorization", required = false) String token) {
        if (userService != null) {
            com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
            if (user != null && "APPROVER".equalsIgnoreCase(user.getRole())) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "message", "Approver role cannot launch batch jobs."));
            }
        }
        JobLaunchResponse response = batchJobService.launchIngestionJob(request);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<java.util.List<com.enterprise.xmldbtool.model.JobExecutionSummary>> getJobHistory() {
        return ResponseEntity.ok(batchJobService.getAllJobExecutions());
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

    @PostMapping("/{jobExecutionId}/resume")
    public ResponseEntity<?> resumeJob(@PathVariable("jobExecutionId") Long jobExecutionId, @RequestHeader(value = "Authorization", required = false) String token) {
        if (userService != null) {
            com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
            if (user != null && "APPROVER".equalsIgnoreCase(user.getRole())) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "message", "Approver role cannot resume or execute jobs."));
            }
        }
        JobLaunchResponse response = batchJobService.resumeJob(jobExecutionId);
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/{jobExecutionId}/restart")
    public ResponseEntity<?> restartJob(@PathVariable("jobExecutionId") Long jobExecutionId, @RequestHeader(value = "Authorization", required = false) String token) {
        return resumeJob(jobExecutionId, token);
    }
}
