package com.enterprise.xmldbtool.model;

import java.util.*;

public class SavedJobConfig {
    private Long id;
    private String jobName;
    private String description;
    private String sourceXmlPath;
    private String recordRootElement = "record";
    private List<String> recordRootElements = new ArrayList<>();
    private String xmlVersion;
    private String schemaVersion;
    private String targetTable;
    private List<String> tableOrder = new ArrayList<>();
    private int chunkSize = 1000;
    private DbConnectionConfig dbConfig;
    private List<FieldMapping> mappings = new ArrayList<>();
    private String cronExpression;
    private String status = "READY";
    private String approvalStatus = "PENDING_APPROVAL"; // PENDING_APPROVAL, APPROVED, REJECTED, DRAFT
    private String reviewedBy;
    private String reviewedAt;
    private String rejectionReason;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
    private String lastRunAt;
    private String lastRunStatus;
    private Long lastJobExecutionId;
    private Boolean stagedPipeline = false;

    public SavedJobConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getRecordRootElement() { return recordRootElement; }
    public void setRecordRootElement(String recordRootElement) { this.recordRootElement = recordRootElement; }

    public List<String> getRecordRootElements() {
        Set<String> set = new LinkedHashSet<>();
        if (recordRootElements != null) {
            for (String r : recordRootElements) {
                if (r != null && !r.trim().isEmpty()) {
                    for (String part : r.split("[,;\\s]+")) {
                        String s = com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.resolveEntityTag(part);
                        if (s != null && !s.isEmpty() && !com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.isWrapperContainerTag(s, null)) {
                            set.add(s);
                        }
                    }
                }
            }
        }
        if (recordRootElement != null && !recordRootElement.trim().isEmpty()) {
            for (String part : recordRootElement.split("[,;\\s]+")) {
                String s = com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.resolveEntityTag(part);
                if (s != null && !s.isEmpty() && !com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.isWrapperContainerTag(s, null)) {
                    set.add(s);
                }
            }
        }
        return set.isEmpty() ? List.of("record") : new ArrayList<>(set);
    }

    public void setRecordRootElements(List<String> recordRootElements) {
        this.recordRootElements = recordRootElements;
    }

    public String getXmlVersion() { return xmlVersion; }
    public void setXmlVersion(String xmlVersion) { this.xmlVersion = xmlVersion; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public DbConnectionConfig getDbConfig() { return dbConfig; }
    public void setDbConfig(DbConnectionConfig dbConfig) { this.dbConfig = dbConfig; }

    public List<FieldMapping> getMappings() { return mappings; }
    public void setMappings(List<FieldMapping> mappings) { this.mappings = mappings; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(String lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }

    public Long getLastJobExecutionId() { return lastJobExecutionId; }
    public void setLastJobExecutionId(Long lastJobExecutionId) { this.lastJobExecutionId = lastJobExecutionId; }

    public Boolean getStagedPipeline() { return stagedPipeline; }
    public void setStagedPipeline(Boolean stagedPipeline) { this.stagedPipeline = stagedPipeline; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public String getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(String reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}
