package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SchemaDiscoveryRequest {
    private DbConnectionConfig dbConfig;
    private String sourceXmlPath;
    private String recordRootElement = "record";
    private List<String> recordRootElements = new ArrayList<>();
    private int sampleLinesLimit = 1000;
    private boolean autoCreateTables = true;
    private String xmlVersion;
    private String schemaVersion;
    private List<FieldMapping> mappings = new ArrayList<>();
    private String mappingJson;
    private String ingestionJsonPath;

    public SchemaDiscoveryRequest() {}

    public List<FieldMapping> getMappings() { return mappings; }
    public void setMappings(List<FieldMapping> mappings) { this.mappings = mappings; }

    public String getMappingJson() { return mappingJson; }
    public void setMappingJson(String mappingJson) { this.mappingJson = mappingJson; }

    public String getIngestionJsonPath() { return ingestionJsonPath; }
    public void setIngestionJsonPath(String ingestionJsonPath) { this.ingestionJsonPath = ingestionJsonPath; }

    public DbConnectionConfig getDbConfig() { return dbConfig; }
    public void setDbConfig(DbConnectionConfig dbConfig) { this.dbConfig = dbConfig; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getRecordRootElement() { return recordRootElement; }
    public void setRecordRootElement(String recordRootElement) { this.recordRootElement = recordRootElement; }

    public List<String> getRecordRootElements() {
        if (recordRootElements != null && !recordRootElements.isEmpty()) {
            return recordRootElements;
        }
        if (recordRootElement != null && !recordRootElement.trim().isEmpty()) {
            return Arrays.stream(recordRootElement.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of("record");
    }

    public void setRecordRootElements(List<String> recordRootElements) {
        this.recordRootElements = recordRootElements;
    }

    public int getSampleLinesLimit() { return sampleLinesLimit; }
    public void setSampleLinesLimit(int sampleLinesLimit) { this.sampleLinesLimit = sampleLinesLimit; }

    public boolean isAutoCreateTables() { return autoCreateTables; }
    public void setAutoCreateTables(boolean autoCreateTables) { this.autoCreateTables = autoCreateTables; }

    public String getXmlVersion() { return xmlVersion; }
    public void setXmlVersion(String xmlVersion) { this.xmlVersion = xmlVersion; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
}
