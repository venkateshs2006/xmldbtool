package com.enterprise.xmldbtool.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class JobTelemetryResponse {
    private Long jobExecutionId;
    private String status;
    private String exitCode;
    private long totalRead;
    private long totalWritten;
    private long totalErrors;
    private double recordsPerSecond;
    private String startTime;
    private String endTime;
    private String message;
    private List<MigrationErrorRecord> recentErrors = new ArrayList<>();

    public JobTelemetryResponse() {}

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getExitCode() { return exitCode; }
    public void setExitCode(String exitCode) { this.exitCode = exitCode; }

    public long getTotalRead() { return totalRead; }
    public void setTotalRead(long totalRead) { this.totalRead = totalRead; }

    public long getTotalWritten() { return totalWritten; }
    public void setTotalWritten(long totalWritten) { this.totalWritten = totalWritten; }

    public long getTotalErrors() { return totalErrors; }
    public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }

    public double getRecordsPerSecond() { return recordsPerSecond; }
    public void setRecordsPerSecond(double recordsPerSecond) { this.recordsPerSecond = recordsPerSecond; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public List<MigrationErrorRecord> getRecentErrors() { return recentErrors; }
    public void setRecentErrors(List<MigrationErrorRecord> recentErrors) { this.recentErrors = recentErrors; }
}
