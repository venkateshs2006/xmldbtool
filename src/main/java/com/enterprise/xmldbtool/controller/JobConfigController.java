package com.enterprise.xmldbtool.controller;

import com.enterprise.xmldbtool.model.JobLaunchRequest;
import com.enterprise.xmldbtool.model.JobLaunchResponse;
import com.enterprise.xmldbtool.model.SavedJobConfig;
import com.enterprise.xmldbtool.service.DynamicBatchJobService;
import com.enterprise.xmldbtool.service.JobConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/job-configs")
public class JobConfigController {

    private final JobConfigService jobConfigService;
    private final DynamicBatchJobService batchJobService;
    private final com.enterprise.xmldbtool.service.UserService userService;

    public JobConfigController(JobConfigService jobConfigService, DynamicBatchJobService batchJobService, com.enterprise.xmldbtool.service.UserService userService) {
        this.jobConfigService = jobConfigService;
        this.batchJobService = batchJobService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<SavedJobConfig>> getAllConfigs() {
        return ResponseEntity.ok(jobConfigService.getAllJobConfigs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getConfigById(@PathVariable Long id) {
        SavedJobConfig config = jobConfigService.getJobConfigById(id);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job configuration not found"));
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping
    public ResponseEntity<?> createConfig(@RequestBody SavedJobConfig config) {
        try {
            SavedJobConfig saved = jobConfigService.saveJobConfig(config);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateConfig(@PathVariable Long id, @RequestBody SavedJobConfig config) {
        try {
            SavedJobConfig updated = jobConfigService.updateJobConfig(id, config);
            return ResponseEntity.ok(updated);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteConfig(@PathVariable Long id) {
        boolean deleted = jobConfigService.deleteJobConfig(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job configuration not found"));
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Job configuration deleted successfully"));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveJob(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String token) {
        com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole() != null ? user.getRole().toUpperCase() : "";
            if (!"APPROVER".equals(role) && !"ADMIN".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Approver or Admin can approve jobs."));
            }
        }
        String approverName = user != null ? user.getUsername() : "approver";
        try {
            SavedJobConfig approved = jobConfigService.approveJob(id, approverName);
            return ResponseEntity.ok(approved);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectJob(@PathVariable Long id, @RequestBody Map<String, String> body, @RequestHeader(value = "Authorization", required = false) String token) {
        com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole() != null ? user.getRole().toUpperCase() : "";
            if (!"APPROVER".equals(role) && !"ADMIN".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Approver or Admin can reject jobs."));
            }
        }
        String reason = body != null ? body.get("reason") : null;
        if (reason == null || reason.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Rejection reason is required."));
        }
        String rejectorName = user != null ? user.getUsername() : "approver";
        try {
            SavedJobConfig rejected = jobConfigService.rejectJob(id, rejectorName, reason.trim());
            return ResponseEntity.ok(rejected);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submitJob(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String token) {
        com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
        String submitterName = user != null ? user.getUsername() : "planner";
        try {
            SavedJobConfig submitted = jobConfigService.submitJobForApproval(id, submitterName);
            return ResponseEntity.ok(submitted);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runJobFromConfig(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String token) {
        com.enterprise.xmldbtool.model.User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole() != null ? user.getRole().toUpperCase() : "";
            if ("APPROVER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "message", "Approver role cannot execute jobs. Jobs must be triggered by Planner or Admin."
                ));
            }
        }

        SavedJobConfig config = jobConfigService.getJobConfigById(id);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job configuration not found"));
        }

        String appStatus = config.getApprovalStatus() != null ? config.getApprovalStatus().toUpperCase() : "PENDING_APPROVAL";
        if ("REJECTED".equals(appStatus)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Job #" + id + " was REJECTED by Approver (" + (config.getReviewedBy() != null ? config.getReviewedBy() : "Approver")
                            + ") and cannot be executed. Reason: " + (config.getRejectionReason() != null ? config.getRejectionReason() : "Configuration rejected.")
            ));
        }

        if ("PENDING_APPROVAL".equals(appStatus) && (user == null || !"ADMIN".equalsIgnoreCase(user.getRole()))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Job #" + id + " is PENDING APPROVAL. It must be reviewed and approved by an Approver before execution."
            ));
        }

        // Construct JobLaunchRequest from saved configuration
        JobLaunchRequest launchRequest = new JobLaunchRequest();
        launchRequest.setSourceXmlPath(config.getSourceXmlPath());
        launchRequest.setRecordRootElement(config.getRecordRootElement());
        launchRequest.setTargetTable(config.getTargetTable());
        launchRequest.setTableOrder(config.getTableOrder());
        launchRequest.setChunkSize(config.getChunkSize());
        launchRequest.setDbConfig(config.getDbConfig());
        launchRequest.setMappings(config.getMappings());
        launchRequest.setStagedPipeline(Boolean.TRUE.equals(config.getStagedPipeline()));
        launchRequest.setRecordRootElements(config.getRecordRootElements());
        launchRequest.setXmlVersion(config.getXmlVersion());
        launchRequest.setSchemaVersion(config.getSchemaVersion());

        JobLaunchResponse response = batchJobService.launchIngestionJob(launchRequest);
        if (response.isSuccess() && response.getJobExecutionId() != null) {
            jobConfigService.updateExecutionStatus(id, response.getJobExecutionId(), "RUNNING");
        }

        return ResponseEntity.ok(response);
    }
}
