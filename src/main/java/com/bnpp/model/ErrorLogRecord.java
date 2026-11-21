package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorLogRecord {
    private String errorId;
    private String fileId;
    private String jobExecutionId;
    private String entityType;
    private String recordIdentifier;
    private String errorSeverity;
    private String errorType;
    private String errorMessage;
    private String stackTrace;
    private String failedPayload;
    private Timestamp timestamp;

    public ErrorLogRecord() {
        this.timestamp = Timestamp.from(Instant.now());
    }

    public String getErrorId() {
        return errorId;
    }

    public void setErrorId(String errorId) {
        this.errorId = errorId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getRecordIdentifier() {
        return recordIdentifier;
    }

    public void setRecordIdentifier(String recordIdentifier) {
        this.recordIdentifier = recordIdentifier;
    }

    public String getErrorSeverity() {
        return errorSeverity;
    }

    public void setErrorSeverity(String errorSeverity) {
        this.errorSeverity = errorSeverity;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getFailedPayload() {
        return failedPayload;
    }

    public void setFailedPayload(String failedPayload) {
        this.failedPayload = failedPayload;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Timestamp timestamp) {
        this.timestamp = timestamp;
    }

    // Frontend compatibility getters
    public String getPhase() {
        return entityType != null ? entityType : (errorType != null ? errorType : "BATCH_ERROR");
    }

    public String getStepName() {
        return entityType != null ? entityType : "dynamicIngestStep";
    }

    public String getRecordData() {
        return failedPayload != null ? failedPayload : (stackTrace != null ? stackTrace : "{}");
    }

    public String getFailedAt() {
        return timestamp != null ? timestamp.toString() : "";
    }
}
