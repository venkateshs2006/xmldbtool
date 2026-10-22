package com.enterprise.xmldbtool.model;

public class MigrationErrorRecord {
    private Long id;
    private Long jobExecutionId;
    private String stepName;
    private String phase;
    private String recordData;
    private String errorMessage;
    private String failedAt;

    public MigrationErrorRecord() {}

    public MigrationErrorRecord(Long jobExecutionId, String stepName, String phase, String recordData, String errorMessage, String failedAt) {
        this.jobExecutionId = jobExecutionId;
        this.stepName = stepName;
        this.phase = phase;
        this.recordData = recordData;
        this.errorMessage = errorMessage;
        this.failedAt = failedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getRecordData() { return recordData; }
    public void setRecordData(String recordData) { this.recordData = recordData; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getFailedAt() { return failedAt; }
    public void setFailedAt(String failedAt) { this.failedAt = failedAt; }
}
