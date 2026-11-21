package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaRegistryVersion {
    private Long id;
    private String vendorName;
    private String vendorId;
    private String versionName;
    private Integer versionNumber;
    private String ingestionJson;
    private String createdBy;
    private Timestamp createdAt;

    public SchemaRegistryVersion() {
        this.createdAt = Timestamp.from(Instant.now());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public Integer getVersionNumber() {
        return versionNumber != null ? versionNumber : 1;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getIngestionJson() {
        return ingestionJson;
    }

    public void setIngestionJson(String ingestionJson) {
        this.ingestionJson = ingestionJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
