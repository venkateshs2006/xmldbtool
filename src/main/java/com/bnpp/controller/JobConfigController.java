package com.bnpp.controller;

import com.bnpp.model.JobLaunchRequest;
import com.bnpp.model.JobLaunchResponse;
import com.bnpp.model.User;
import com.bnpp.service.BatchPipelineJobService;
import com.bnpp.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/job-configs")
public class JobConfigController {

    private final BatchPipelineJobService batchPipelineJobService;
    private final UserService userService;

    public JobConfigController(BatchPipelineJobService batchPipelineJobService, UserService userService) {
        this.batchPipelineJobService = batchPipelineJobService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<JobLaunchRequest>> getAllJobs() {
        return ResponseEntity.ok(batchPipelineJobService.getAllJobs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJobById(@PathVariable String id) {
        Optional<JobLaunchRequest> jobOpt = batchPipelineJobService.getJobConfig(id);
        return jobOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    @PostMapping
    public ResponseEntity<?> submitJob(@RequestBody JobLaunchRequest req,
                                       @RequestHeader(value = "Authorization", required = false) String token) {
        User user = userService.validateToken(token);
        String submitter = user != null ? user.getUsername() : "scheduler";
        try {
            JobLaunchResponse resp = batchPipelineJobService.submitJob(req, submitter);
            Map<String, Object> body = Map.of(
                    "success", true,
                    "id", req.getId(),
                    "jobId", req.getJobId(),
                    "jobExecutionId", req.getJobExecutionId() != null ? req.getJobExecutionId() : "",
                    "status", req.getStatus(),
                    "approvalStatus", req.getApprovalStatus(),
                    "message", "Job submitted successfully."
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveJob(@PathVariable String id,
                                       @RequestHeader(value = "Authorization", required = false) String token) {
        User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole().toUpperCase();
            if (!"APPROVER".equals(role) && !"ADMIN".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Approver or Admin can approve jobs."));
            }
        }
        String approver = user != null ? user.getUsername() : "approver";
        try {
            JobLaunchResponse resp = batchPipelineJobService.approveJob(id, approver);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectJob(@PathVariable String id,
                                      @RequestBody Map<String, String> body,
                                      @RequestHeader(value = "Authorization", required = false) String token) {
        User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole().toUpperCase();
            if (!"APPROVER".equals(role) && !"ADMIN".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Approver or Admin can reject jobs."));
            }
        }
        String reason = body != null ? body.get("reason") : "Rejected by approver";
        String approver = user != null ? user.getUsername() : "approver";
        try {
            JobLaunchResponse resp = batchPipelineJobService.rejectJob(id, approver, reason);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runJob(@PathVariable String id,
                                    @RequestHeader(value = "Authorization", required = false) String token) {
        User user = userService.validateToken(token);
        if (user != null) {
            String role = user.getRole().toUpperCase();
            if ("APPROVER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Approver cannot execute jobs."));
            }
        }
        String runner = user != null ? user.getUsername() : "scheduler";
        try {
            JobLaunchResponse resp = batchPipelineJobService.runJob(id, runner);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteJob(@PathVariable String id) {
        boolean deleted = batchPipelineJobService.deleteJob(id);
        if (deleted) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Job deleted successfully."));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job not found."));
        }
    }
}
