package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JobCheckpointRecord {
    private String jobExecutionId;
    private String fileId;
    private String lastRecordIdentifier;
    private long recordsStreamed;
    private String status;
    private Timestamp updatedAt;

    public JobCheckpointRecord() {
        this.updatedAt = Timestamp.from(Instant.now());
    }

    public JobCheckpointRecord(String jobExecutionId, String fileId, String lastRecordIdentifier, long recordsStreamed, String status) {
        this.jobExecutionId = jobExecutionId;
        this.fileId = fileId;
        this.lastRecordIdentifier = lastRecordIdentifier;
        this.recordsStreamed = recordsStreamed;
        this.status = status;
        this.updatedAt = Timestamp.from(Instant.now());
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getLastRecordIdentifier() {
        return lastRecordIdentifier;
    }

    public void setLastRecordIdentifier(String lastRecordIdentifier) {
        this.lastRecordIdentifier = lastRecordIdentifier;
    }

    public long getRecordsStreamed() {
        return recordsStreamed;
    }

    public void setRecordsStreamed(long recordsStreamed) {
        this.recordsStreamed = recordsStreamed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
