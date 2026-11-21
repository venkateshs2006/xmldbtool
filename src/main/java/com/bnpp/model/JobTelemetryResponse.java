package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JobTelemetryResponse {
    private String jobExecutionId;
    private String executionId;
    private String jobId;
    private String status; // RUNNING, COMPLETED, FAILED, STOPPED, PENDING_APPROVAL, APPROVED, REJECTED
    private boolean success = true;
    private long recordsRead;
    private long recordsWritten;
    private long recordsSkipped;
    private long recordsFailed;
    private long totalRecords;
    private Long totalWritten;
    private Long totalRead;
    private double throughput; // overall records/sec
    private Double recordsPerSecond;
    private double readThroughput; // read records/sec
    private double writeThroughput; // write records/sec
    private int completedTables;
    private int pendingTables;
    private int cpuCores;
    private double cpuUsage;
    private double memoryUsage;
    private long elapsedTimeMs;
    private long startTimeMs = System.currentTimeMillis();
    private String currentTag;
    private String errorMessage;
    private String message;
    private List<String> targetTables;
    private Map<String, Long> tableCounts = new HashMap<>();
    private Map<String, Long> tableRowCounts = new HashMap<>();
    private Map<String, Long> tableWriteCounts = new HashMap<>();
    private Map<String, Long> tableErrorCounts = new HashMap<>();
    private String streamingState;
    private String startTime;
    private String endTime;
    private String duration;
    private String currentAction;
    private String currentPhase;
    private List<AuditLogRecord> auditLogs;
    private List<ErrorLogRecord> errorLogs;
    private List<MigrationErrorRecord> recentErrors;
    private List<FileHistoryRecord> fileHistory;

    public JobTelemetryResponse() {
        this.cpuCores = Runtime.getRuntime().availableProcessors();
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getStatus() {
        return status != null ? status : "UNKNOWN";
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getRecordsRead() {
        return recordsRead;
    }

    public void setRecordsRead(long recordsRead) {
        this.recordsRead = recordsRead;
    }

    public long getRecordsWritten() {
        return recordsWritten;
    }

    public void setRecordsWritten(long recordsWritten) {
        this.recordsWritten = recordsWritten;
    }

    public long getRecordsSkipped() {
        return recordsSkipped;
    }

    public void setRecordsSkipped(long recordsSkipped) {
        this.recordsSkipped = recordsSkipped;
    }

    public long getRecordsFailed() {
        return recordsFailed;
    }

    public void setRecordsFailed(long recordsFailed) {
        this.recordsFailed = recordsFailed;
    }

    public long getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(long totalRecords) {
        this.totalRecords = totalRecords;
    }

    public double getThroughput() {
        return throughput;
    }

    public void setThroughput(double throughput) {
        this.throughput = throughput;
    }

    public double getReadThroughput() {
        return readThroughput;
    }

    public void setReadThroughput(double readThroughput) {
        this.readThroughput = readThroughput;
    }

    public double getWriteThroughput() {
        return writeThroughput;
    }

    public void setWriteThroughput(double writeThroughput) {
        this.writeThroughput = writeThroughput;
    }

    public int getCompletedTables() {
        return completedTables;
    }

    public void setCompletedTables(int completedTables) {
        this.completedTables = completedTables;
    }

    public int getPendingTables() {
        return pendingTables;
    }

    public void setPendingTables(int pendingTables) {
        this.pendingTables = pendingTables;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public void setCpuCores(int cpuCores) {
        this.cpuCores = cpuCores;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public double getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public long getElapsedTimeMs() {
        return elapsedTimeMs;
    }

    public void setElapsedTimeMs(long elapsedTimeMs) {
        this.elapsedTimeMs = elapsedTimeMs;
    }

    public String getCurrentTag() {
        return currentTag;
    }

    public void setCurrentTag(String currentTag) {
        this.currentTag = currentTag;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Long> getTableCounts() {
        return tableCounts != null ? tableCounts : Collections.emptyMap();
    }

    public void setTableCounts(Map<String, Long> tableCounts) {
        this.tableCounts = tableCounts;
    }

    public Map<String, Long> getTableRowCounts() {
        return tableRowCounts != null ? tableRowCounts : Collections.emptyMap();
    }

    public void setTableRowCounts(Map<String, Long> tableRowCounts) {
        this.tableRowCounts = tableRowCounts;
    }

    public String getExecutionId() {
        return executionId != null ? executionId : jobExecutionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
        if (this.jobExecutionId == null) {
            this.jobExecutionId = executionId;
        }
    }

    public long getTotalWritten() {
        return totalWritten != null ? totalWritten : recordsWritten;
    }

    public void setTotalWritten(long totalWritten) {
        this.totalWritten = totalWritten;
        this.recordsWritten = totalWritten;
        if (this.totalRecords == 0) {
            this.totalRecords = totalWritten;
        }
    }

    public long getTotalRead() {
        return totalRead != null ? totalRead : recordsRead;
    }

    public void setTotalRead(long totalRead) {
        this.totalRead = totalRead;
        this.recordsRead = totalRead;
    }

    public double getRecordsPerSecond() {
        return recordsPerSecond != null ? recordsPerSecond : throughput;
    }

    public void setRecordsPerSecond(double recordsPerSecond) {
        this.recordsPerSecond = recordsPerSecond;
        this.throughput = recordsPerSecond;
    }

    public String getMessage() {
        return message != null ? message : errorMessage;
    }

    public void setMessage(String message) {
        this.message = message;
        if (this.errorMessage == null) {
            this.errorMessage = message;
        }
    }

    public List<String> getTargetTables() {
        if (targetTables != null && !targetTables.isEmpty()) {
            return targetTables;
        }
        Map<String, Long> writes = getTableWriteCounts();
        if (writes != null && !writes.isEmpty()) {
            return new java.util.ArrayList<>(writes.keySet());
        }
        return Collections.emptyList();
    }

    public void setTargetTables(List<String> targetTables) {
        this.targetTables = targetTables;
    }

    public Map<String, Long> getTableWriteCounts() {
        if (tableWriteCounts != null && !tableWriteCounts.isEmpty()) {
            return tableWriteCounts;
        }
        if (tableRowCounts != null && !tableRowCounts.isEmpty()) {
            return tableRowCounts;
        }
        return tableCounts != null ? tableCounts : Collections.emptyMap();
    }

    public void setTableWriteCounts(Map<String, Long> tableWriteCounts) {
        this.tableWriteCounts = tableWriteCounts;
        if (tableWriteCounts != null) {
            if (this.tableCounts.isEmpty()) this.tableCounts = new HashMap<>(tableWriteCounts);
            if (this.tableRowCounts.isEmpty()) this.tableRowCounts = new HashMap<>(tableWriteCounts);
        }
    }

    public Map<String, Long> getTableErrorCounts() {
        return tableErrorCounts != null ? tableErrorCounts : Collections.emptyMap();
    }

    public void setTableErrorCounts(Map<String, Long> tableErrorCounts) {
        this.tableErrorCounts = tableErrorCounts;
    }

    public String getStreamingState() {
        if (streamingState != null) {
            return streamingState;
        }
        return ("RUNNING".equalsIgnoreCase(status) || "STARTED".equalsIgnoreCase(status)) ? "STREAMING" : status;
    }

    public void setStreamingState(String streamingState) {
        this.streamingState = streamingState;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public void setStartTimeMs(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    public String getStartTime() {
        return startTime;
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

    public String getCurrentAction() {
        return currentAction;
    }

    public void setCurrentAction(String currentAction) {
        this.currentAction = currentAction;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    public List<AuditLogRecord> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<AuditLogRecord> auditLogs) {
        this.auditLogs = auditLogs;
    }

    public List<ErrorLogRecord> getErrorLogs() {
        return errorLogs;
    }

    public void setErrorLogs(List<ErrorLogRecord> errorLogs) {
        this.errorLogs = errorLogs;
    }

    public List<?> getRecentErrors() {
        if (recentErrors != null && !recentErrors.isEmpty()) {
            return recentErrors;
        }
        return errorLogs != null ? errorLogs : Collections.emptyList();
    }

    public void setRecentErrors(List<MigrationErrorRecord> recentErrors) {
        this.recentErrors = recentErrors;
    }

    public List<FileHistoryRecord> getFileHistory() {
        return fileHistory;
    }

    public void setFileHistory(List<FileHistoryRecord> fileHistory) {
        this.fileHistory = fileHistory;
    }
}
