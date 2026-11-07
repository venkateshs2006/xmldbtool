package com.enterprise.xmldbtool.model;

import java.time.LocalDateTime;

public class JobCheckpoint {
    private Long id;
    private Long jobExecutionId;
    private String jobName;
    private String sourceXmlPath;
    private String stage; // STAGE_1_STREAMING, STAGE_2_NORMALIZATION, COMPLETED, STOPPED, FAILED
    private long recordsStreamed;
    private long recordsNormalized;
    private String lastRecordIdentifier;
    private String lastTagName;
    private String status;
    private String checkpointData;
    private LocalDateTime updatedAt;

    public JobCheckpoint() {}

    public JobCheckpoint(Long jobExecutionId, String jobName, String sourceXmlPath, String stage,
                         long recordsStreamed, long recordsNormalized, String lastRecordIdentifier,
                         String lastTagName, String status) {
        this.jobExecutionId = jobExecutionId;
        this.jobName = jobName;
        this.sourceXmlPath = sourceXmlPath;
        this.stage = stage;
        this.recordsStreamed = recordsStreamed;
        this.recordsNormalized = recordsNormalized;
        this.lastRecordIdentifier = lastRecordIdentifier;
        this.lastTagName = lastTagName;
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public long getRecordsStreamed() { return recordsStreamed; }
    public void setRecordsStreamed(long recordsStreamed) { this.recordsStreamed = recordsStreamed; }

    public long getRecordsNormalized() { return recordsNormalized; }
    public void setRecordsNormalized(long recordsNormalized) { this.recordsNormalized = recordsNormalized; }

    public String getLastRecordIdentifier() { return lastRecordIdentifier; }
    public void setLastRecordIdentifier(String lastRecordIdentifier) { this.lastRecordIdentifier = lastRecordIdentifier; }

    public String getLastTagName() { return lastTagName; }
    public void setLastTagName(String lastTagName) { this.lastTagName = lastTagName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCheckpointData() { return checkpointData; }
    public void setCheckpointData(String checkpointData) { this.checkpointData = checkpointData; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
