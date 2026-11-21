package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FileHistoryRecord {
    private String fileId;
    private String vendorName;
    private String vendorId;
    private String xmlFilename;
    private String filenameHash;
    private String fileChecksum;
    private Long fileSizeKb;
    private Integer fileVersionNumber;
    private Timestamp uploadDate;
    private String fileMetadata;
    private String ingestionFilePath;
    private String ingestionFileContent;
    private Long totalNumberOfRecords;
    private Long gsRecordCountInDb;
    private Long srcRecordCount;
    private String processingStatus;
    private String triggeredBy;
    private String createdBy;
    private String updatedBy;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public FileHistoryRecord() {
        this.createdAt = Timestamp.from(Instant.now());
        this.updatedAt = Timestamp.from(Instant.now());
        this.uploadDate = Timestamp.from(Instant.now());
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public String getXmlFilename() {
        return xmlFilename;
    }

    public void setXmlFilename(String xmlFilename) {
        this.xmlFilename = xmlFilename;
    }

    public String getFilenameHash() {
        return filenameHash;
    }

    public void setFilenameHash(String filenameHash) {
        this.filenameHash = filenameHash;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public void setFileChecksum(String fileChecksum) {
        this.fileChecksum = fileChecksum;
    }

    public Long getFileSizeKb() {
        return fileSizeKb;
    }

    public void setFileSizeKb(Long fileSizeKb) {
        this.fileSizeKb = fileSizeKb;
    }

    public Integer getFileVersionNumber() {
        return fileVersionNumber != null ? fileVersionNumber : 1;
    }

    public void setFileVersionNumber(Integer fileVersionNumber) {
        this.fileVersionNumber = fileVersionNumber;
    }

    public Timestamp getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(Timestamp uploadDate) {
        this.uploadDate = uploadDate;
    }

    public String getFileMetadata() {
        return fileMetadata;
    }

    public void setFileMetadata(String fileMetadata) {
        this.fileMetadata = fileMetadata;
    }

    public String getIngestionFilePath() {
        return ingestionFilePath;
    }

    public void setIngestionFilePath(String ingestionFilePath) {
        this.ingestionFilePath = ingestionFilePath;
    }

    public String getIngestionFileContent() {
        return ingestionFileContent;
    }

    public void setIngestionFileContent(String ingestionFileContent) {
        this.ingestionFileContent = ingestionFileContent;
    }

    public Long getTotalNumberOfRecords() {
        return totalNumberOfRecords;
    }

    public void setTotalNumberOfRecords(Long totalNumberOfRecords) {
        this.totalNumberOfRecords = totalNumberOfRecords;
    }

    public Long getGsRecordCountInDb() {
        return gsRecordCountInDb;
    }

    public void setGsRecordCountInDb(Long gsRecordCountInDb) {
        this.gsRecordCountInDb = gsRecordCountInDb;
    }

    public Long getSrcRecordCount() {
        return srcRecordCount;
    }

    public void setSrcRecordCount(Long srcRecordCount) {
        this.srcRecordCount = srcRecordCount;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
