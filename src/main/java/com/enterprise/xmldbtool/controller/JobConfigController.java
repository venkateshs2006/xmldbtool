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

    public JobConfigController(JobConfigService jobConfigService, DynamicBatchJobService batchJobService) {
        this.jobConfigService = jobConfigService;
        this.batchJobService = batchJobService;
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

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runJobFromConfig(@PathVariable Long id) {
        SavedJobConfig config = jobConfigService.getJobConfigById(id);
        if (config == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job configuration not found"));
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

        JobLaunchResponse response = batchJobService.launchIngestionJob(launchRequest);
        if (response.isSuccess() && response.getJobExecutionId() != null) {
            jobConfigService.updateExecutionStatus(id, response.getJobExecutionId(), "RUNNING");
        }

        return ResponseEntity.ok(response);
    }
}
