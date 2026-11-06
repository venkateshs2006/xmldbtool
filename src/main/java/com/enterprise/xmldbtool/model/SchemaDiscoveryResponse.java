package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;

public class SchemaDiscoveryResponse {
    private boolean success;
    private String message;
    private List<DiscoveredElement> discoveredXmlElements = new ArrayList<>();
    private List<TableMetadata> discoveredTables = new ArrayList<>();
    private List<String> createdTables = new ArrayList<>();
    private List<String> candidateRootElements = new ArrayList<>();
    private String detectedXmlVersion;
    private String detectedRootElement;
    private List<String> createdFlatTables = new ArrayList<>();
    private List<String> stagingTables = new ArrayList<>();
    private List<FieldMapping> generatedMappings = new ArrayList<>();
    private List<TableRelationship> discoveredRelationships = new ArrayList<>();
    private List<String> tableOrder = new ArrayList<>();

    public SchemaDiscoveryResponse() {}

    public SchemaDiscoveryResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<DiscoveredElement> getDiscoveredXmlElements() { return discoveredXmlElements; }
    public void setDiscoveredXmlElements(List<DiscoveredElement> discoveredXmlElements) { this.discoveredXmlElements = discoveredXmlElements; }

    public List<TableMetadata> getDiscoveredTables() { return discoveredTables; }
    public void setDiscoveredTables(List<TableMetadata> discoveredTables) { this.discoveredTables = discoveredTables; }

    public List<String> getCreatedTables() { return createdTables; }
    public void setCreatedTables(List<String> createdTables) { this.createdTables = createdTables; }

    public List<String> getCandidateRootElements() { return candidateRootElements; }
    public void setCandidateRootElements(List<String> candidateRootElements) { this.candidateRootElements = candidateRootElements; }

    public String getDetectedXmlVersion() { return detectedXmlVersion; }
    public void setDetectedXmlVersion(String detectedXmlVersion) { this.detectedXmlVersion = detectedXmlVersion; }

    public String getDetectedRootElement() { return detectedRootElement; }
    public void setDetectedRootElement(String detectedRootElement) { this.detectedRootElement = detectedRootElement; }

    public List<String> getCreatedFlatTables() { return createdFlatTables; }
    public void setCreatedFlatTables(List<String> createdFlatTables) { this.createdFlatTables = createdFlatTables; }

    public List<String> getStagingTables() { return stagingTables; }
    public void setStagingTables(List<String> stagingTables) { this.stagingTables = stagingTables; }

    public List<FieldMapping> getGeneratedMappings() { return generatedMappings; }
    public void setGeneratedMappings(List<FieldMapping> generatedMappings) { this.generatedMappings = generatedMappings; }

    public List<TableRelationship> getDiscoveredRelationships() { return discoveredRelationships; }
    public void setDiscoveredRelationships(List<TableRelationship> discoveredRelationships) { this.discoveredRelationships = discoveredRelationships; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    private long fileSizeBytes;
    private String fileSizeFormatted;

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getFileSizeFormatted() { return fileSizeFormatted; }
    public void setFileSizeFormatted(String fileSizeFormatted) { this.fileSizeFormatted = fileSizeFormatted; }
}
