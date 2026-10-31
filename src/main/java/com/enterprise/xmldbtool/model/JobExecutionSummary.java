package com.enterprise.xmldbtool.model;

public class JobExecutionSummary {
    private Long executionId;
    private String jobName;
    private String targetTable;
    private java.util.List<String> targetTables = new java.util.ArrayList<>();
    private java.util.Map<String, Long> tableWriteCounts = new java.util.LinkedHashMap<>();
    private java.util.Map<String, Long> tableErrorCounts = new java.util.LinkedHashMap<>();
    private String status;
    private String exitCode;
    private String startTime;
    private String endTime;
    private String duration;
    private long totalRead;
    private long totalWritten;
    private long totalErrors;
    private double recordsPerSecond;

    public JobExecutionSummary() {}

    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public java.util.List<String> getTargetTables() { return targetTables; }
    public void setTargetTables(java.util.List<String> targetTables) { this.targetTables = targetTables != null ? targetTables : new java.util.ArrayList<>(); }

    public java.util.Map<String, Long> getTableWriteCounts() { return tableWriteCounts; }
    public void setTableWriteCounts(java.util.Map<String, Long> tableWriteCounts) { this.tableWriteCounts = tableWriteCounts != null ? tableWriteCounts : new java.util.LinkedHashMap<>(); }

    public java.util.Map<String, Long> getTableErrorCounts() { return tableErrorCounts; }
    public void setTableErrorCounts(java.util.Map<String, Long> tableErrorCounts) { this.tableErrorCounts = tableErrorCounts != null ? tableErrorCounts : new java.util.LinkedHashMap<>(); }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getExitCode() { return exitCode; }
    public void setExitCode(String exitCode) { this.exitCode = exitCode; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public long getTotalRead() { return totalRead; }
    public void setTotalRead(long totalRead) { this.totalRead = totalRead; }

    public long getTotalWritten() { return totalWritten; }
    public void setTotalWritten(long totalWritten) { this.totalWritten = totalWritten; }

    public long getTotalErrors() { return totalErrors; }
    public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }

    public double getRecordsPerSecond() { return recordsPerSecond; }
    public void setRecordsPerSecond(double recordsPerSecond) { this.recordsPerSecond = recordsPerSecond; }
}
