package com.enterprise.xmldbtool.model;

import java.time.LocalDateTime;

/**
 * Model representing a flat staged XML record.
 * Staged records decouple memory-efficient StAX streaming ingestion
 * from relational canonical normalization, delta processing, and schema evolution.
 */
public class StagingXmlRecord {

    private Long id;
    private Long jobExecutionId;
    private String entityType;
    private String recordIdentifier;
    private String recordAction;
    private String recordDate;
    private String rawPayload;
    private String payloadHash;
    private String bnppId;
    private String tagName;
    private Integer versionNumber = 1;
    private String xmlVersion;
    private String schemaVersion;
    private String status; // STAGED, NORMALIZED, SKIPPED_UNCHANGED, FAILED
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private transient java.util.Map<String, Object> cachedMap;

    public StagingXmlRecord() {}

    public StagingXmlRecord(Long jobExecutionId, String entityType, String recordIdentifier,
                            String recordAction, String recordDate, String rawPayload, String payloadHash) {
        this.jobExecutionId = jobExecutionId;
        this.entityType = entityType;
        this.recordIdentifier = recordIdentifier;
        this.recordAction = recordAction;
        this.recordDate = recordDate;
        this.rawPayload = rawPayload;
        this.payloadHash = payloadHash;
        this.status = "STAGED";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getRecordIdentifier() { return recordIdentifier; }
    public void setRecordIdentifier(String recordIdentifier) { this.recordIdentifier = recordIdentifier; }

    public String getRecordAction() { return recordAction; }
    public void setRecordAction(String recordAction) { this.recordAction = recordAction; }

    public String getRecordDate() { return recordDate; }
    public void setRecordDate(String recordDate) { this.recordDate = recordDate; }

    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public String getBnppId() { return bnppId; }
    public void setBnppId(String bnppId) { this.bnppId = bnppId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }

    public String getXmlVersion() { return xmlVersion; }
    public void setXmlVersion(String xmlVersion) { this.xmlVersion = xmlVersion; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public java.util.Map<String, Object> getCachedMap() { return cachedMap; }
    public void setCachedMap(java.util.Map<String, Object> cachedMap) { this.cachedMap = cachedMap; }
}

