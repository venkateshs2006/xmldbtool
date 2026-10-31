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
    private List<String> targetTables = new ArrayList<>();
    private java.util.Map<String, Long> tableWriteCounts = new java.util.LinkedHashMap<>();
    private java.util.Map<String, Long> tableErrorCounts = new java.util.LinkedHashMap<>();

    public JobTelemetryResponse() {}

    public List<String> getTargetTables() { return targetTables; }
    public void setTargetTables(List<String> targetTables) { this.targetTables = targetTables != null ? targetTables : new ArrayList<>(); }

    public java.util.Map<String, Long> getTableWriteCounts() { return tableWriteCounts; }
    public void setTableWriteCounts(java.util.Map<String, Long> tableWriteCounts) { this.tableWriteCounts = tableWriteCounts != null ? tableWriteCounts : new java.util.LinkedHashMap<>(); }

    public java.util.Map<String, Long> getTableErrorCounts() { return tableErrorCounts; }
    public void setTableErrorCounts(java.util.Map<String, Long> tableErrorCounts) { this.tableErrorCounts = tableErrorCounts != null ? tableErrorCounts : new java.util.LinkedHashMap<>(); }

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
