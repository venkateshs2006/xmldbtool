package com.enterprise.xmldbtool.model;

public class JobLaunchResponse {
    private boolean success;
    private String message;
    private Long jobExecutionId;
    private String status;

    public JobLaunchResponse() {}

    public JobLaunchResponse(boolean success, String message, Long jobExecutionId, String status) {
        this.success = success;
        this.message = message;
        this.jobExecutionId = jobExecutionId;
        this.status = status;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
