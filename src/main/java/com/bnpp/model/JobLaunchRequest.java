package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JobLaunchRequest {
    private Long id;
    private String jobId;
    private String jobName;
    private String jobExecutionId;
    private String xmlFilePath;
    private String sourceXmlPath;
    private String ingestionJson;
    private String mappingJson;
    private String ingestMappingJson;
    private String ingestionJsonPath;
    private String recordRootElement;
    private String vendorName;
    private String vendorId;
    private DbConnectionConfig dbConfig;
    private String status = "PENDING_APPROVAL"; // PENDING_APPROVAL, APPROVED, REJECTED, RUNNING, COMPLETED, FAILED, STOPPED
    private String approvalStatus = "PENDING_APPROVAL";
    private String submittedBy;
    private String reviewedBy;
    private String rejectionReason;
    private Timestamp approvedAt;
    private String targetTable;
    private List<FieldMapping> mappings;
    private Integer chunkSize = 1000;
    private Boolean stagedPipeline = true;
    private String lastRunStatus;
    private String lastRunAt;
    private List<String> recordRootElements;
    private List<String> tableOrder;
    private String executionId;
    private Long totalWritten;
    private Long totalRead;
    private Long totalErrors;
    private String startTime;
    private String endTime;
    private String duration;
    private String currentPhase;
    private List<String> targetTables;
    private java.util.Map<String, Long> tableWriteCounts = new java.util.HashMap<>();
    private java.util.Map<String, Long> tableErrorCounts = new java.util.HashMap<>();
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public JobLaunchRequest() {
        this.createdAt = Timestamp.from(Instant.now());
        this.updatedAt = Timestamp.from(Instant.now());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobId() {
        return jobId != null ? jobId : (id != null ? String.valueOf(id) : null);
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getXmlFilePath() {
        return xmlFilePath != null ? xmlFilePath : sourceXmlPath;
    }

    public void setXmlFilePath(String xmlFilePath) {
        this.xmlFilePath = xmlFilePath;
        if (this.sourceXmlPath == null) {
            this.sourceXmlPath = xmlFilePath;
        }
    }

    public String getSourceXmlPath() {
        return sourceXmlPath != null ? sourceXmlPath : xmlFilePath;
    }

    public void setSourceXmlPath(String sourceXmlPath) {
        this.sourceXmlPath = sourceXmlPath;
        if (this.xmlFilePath == null) {
            this.xmlFilePath = sourceXmlPath;
        }
    }

    public String getIngestionJson() {
        if (ingestionJson != null && !ingestionJson.trim().isEmpty()) return ingestionJson;
        if (mappingJson != null && !mappingJson.trim().isEmpty()) return mappingJson;
        if (ingestMappingJson != null && !ingestMappingJson.trim().isEmpty()) return ingestMappingJson;
        if (mappings != null && !mappings.isEmpty()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mappings);
            } catch (Exception ignored) {}
        }
        return ingestionJson;
    }

    public void setIngestionJson(String ingestionJson) {
        this.ingestionJson = ingestionJson;
    }

    public String getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(String mappingJson) {
        this.mappingJson = mappingJson;
        if (this.ingestionJson == null) this.ingestionJson = mappingJson;
    }

    public String getIngestMappingJson() {
        return ingestMappingJson;
    }

    public void setIngestMappingJson(String ingestMappingJson) {
        this.ingestMappingJson = ingestMappingJson;
        if (this.ingestionJson == null) this.ingestionJson = ingestMappingJson;
    }

    public String getIngestionJsonPath() {
        return ingestionJsonPath;
    }

    public void setIngestionJsonPath(String ingestionJsonPath) {
        this.ingestionJsonPath = ingestionJsonPath;
    }

    public String getRecordRootElement() {
        return recordRootElement;
    }

    public void setRecordRootElement(String recordRootElement) {
        this.recordRootElement = recordRootElement;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public DbConnectionConfig getDbConfig() {
        return dbConfig;
    }

    public void setDbConfig(DbConnectionConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApprovalStatus() {
        return approvalStatus != null ? approvalStatus : status;
    }

    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Timestamp getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Timestamp approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public List<FieldMapping> getMappings() {
        if (mappings != null && !mappings.isEmpty()) return mappings;
        String json = getIngestionJson();
        if (json != null && !json.trim().isEmpty()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                        new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {});
            } catch (Exception ignored) {}
        }
        return mappings;
    }

    public void setMappings(List<FieldMapping> mappings) {
        this.mappings = mappings;
    }

    public Integer getChunkSize() {
        return chunkSize != null ? chunkSize : 1000;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Boolean getStagedPipeline() {
        return stagedPipeline != null ? stagedPipeline : true;
    }

    public void setStagedPipeline(Boolean stagedPipeline) {
        this.stagedPipeline = stagedPipeline;
    }

    public String getLastRunStatus() {
        return lastRunStatus != null ? lastRunStatus : status;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(String lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public List<String> getRecordRootElements() {
        return recordRootElements;
    }

    public void setRecordRootElements(List<String> recordRootElements) {
        this.recordRootElements = recordRootElements;
    }

    public List<String> getTableOrder() {
        return tableOrder;
    }

    public void setTableOrder(List<String> tableOrder) {
        this.tableOrder = tableOrder;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getExecutionId() {
        return executionId != null ? executionId : (jobExecutionId != null ? jobExecutionId : jobId);
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
        if (this.jobExecutionId == null) {
            this.jobExecutionId = executionId;
        }
    }

    public Long getTotalWritten() {
        return totalWritten != null ? totalWritten : 0L;
    }

    public void setTotalWritten(Long totalWritten) {
        this.totalWritten = totalWritten;
    }

    public Long getTotalRead() {
        return totalRead != null ? totalRead : (totalWritten != null ? totalWritten : 0L);
    }

    public void setTotalRead(Long totalRead) {
        this.totalRead = totalRead;
    }

    public Long getTotalErrors() {
        return totalErrors != null ? totalErrors : 0L;
    }

    public void setTotalErrors(Long totalErrors) {
        this.totalErrors = totalErrors;
    }

    public String getStartTime() {
        return startTime != null ? startTime : lastRunAt;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    public List<String> getTargetTables() {
        if (targetTables != null && !targetTables.isEmpty()) {
            return targetTables;
        }
        if (tableOrder != null && !tableOrder.isEmpty()) {
            return tableOrder;
        }
        if (targetTable != null && !targetTable.trim().isEmpty()) {
            return java.util.Arrays.stream(targetTable.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        if (tableWriteCounts != null && !tableWriteCounts.isEmpty()) {
            return new java.util.ArrayList<>(tableWriteCounts.keySet());
        }
        return java.util.Collections.emptyList();
    }

    public void setTargetTables(List<String> targetTables) {
        this.targetTables = targetTables;
    }

    public java.util.Map<String, Long> getTableWriteCounts() {
        return tableWriteCounts != null ? tableWriteCounts : java.util.Collections.emptyMap();
    }

    public void setTableWriteCounts(java.util.Map<String, Long> tableWriteCounts) {
        this.tableWriteCounts = tableWriteCounts;
    }

    public java.util.Map<String, Long> getTableErrorCounts() {
        return tableErrorCounts != null ? tableErrorCounts : java.util.Collections.emptyMap();
    }

    public void setTableErrorCounts(java.util.Map<String, Long> tableErrorCounts) {
        this.tableErrorCounts = tableErrorCounts;
    }
}
