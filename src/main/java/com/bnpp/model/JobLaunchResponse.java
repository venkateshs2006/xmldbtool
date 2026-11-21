package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JobLaunchResponse {
    private boolean success;
    private String jobId;
    private String jobExecutionId;
    private String status;
    private String message;
    private Long totalRecords;
    private Long recordsWritten;

    public JobLaunchResponse() {}

    public JobLaunchResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public JobLaunchResponse(boolean success, String jobId, String jobExecutionId, String status, String message) {
        this.success = success;
        this.jobId = jobId;
        this.jobExecutionId = jobExecutionId;
        this.status = status;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(Long totalRecords) {
        this.totalRecords = totalRecords;
    }

    public Long getRecordsWritten() {
        return recordsWritten;
    }

    public void setRecordsWritten(Long recordsWritten) {
        this.recordsWritten = recordsWritten;
    }
}
