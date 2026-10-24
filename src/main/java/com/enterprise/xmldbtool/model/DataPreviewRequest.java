package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.List;

public class DataPreviewRequest {
    private DbConnectionConfig dbConfig;
    private String sourceXmlPath;
    private String recordRootElement = "record";
    private String targetTable;
    private List<FieldMapping> mappings = new ArrayList<>();
    private List<String> tableOrder = new ArrayList<>();
    private int previewLimit = 5;

    public DataPreviewRequest() {}

    public DbConnectionConfig getDbConfig() { return dbConfig; }
    public void setDbConfig(DbConnectionConfig dbConfig) { this.dbConfig = dbConfig; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getRecordRootElement() { return recordRootElement; }
    public void setRecordRootElement(String recordRootElement) { this.recordRootElement = recordRootElement; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public List<FieldMapping> getMappings() { return mappings; }
    public void setMappings(List<FieldMapping> mappings) { this.mappings = mappings; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    public int getPreviewLimit() { return previewLimit; }
    public void setPreviewLimit(int previewLimit) { this.previewLimit = previewLimit; }
}
