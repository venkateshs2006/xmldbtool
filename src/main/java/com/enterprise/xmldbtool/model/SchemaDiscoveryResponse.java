package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;

public class SchemaDiscoveryResponse {
    private boolean success;
    private String message;
    private List<DiscoveredElement> discoveredXmlElements = new ArrayList<>();
    private List<TableMetadata> discoveredTables = new ArrayList<>();
    private List<String> createdTables = new ArrayList<>();

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

    private long fileSizeBytes;
    private String fileSizeFormatted;

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getFileSizeFormatted() { return fileSizeFormatted; }
    public void setFileSizeFormatted(String fileSizeFormatted) { this.fileSizeFormatted = fileSizeFormatted; }
}
