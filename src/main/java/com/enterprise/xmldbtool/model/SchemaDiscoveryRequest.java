package com.enterprise.xmldbtool.model;

import java.util.List;

public class SchemaDiscoveryRequest {
    private DbConnectionConfig dbConfig;
    private String sourceXmlPath;
    private String recordRootElement = "record";
    private int sampleLinesLimit = 1000;

    public SchemaDiscoveryRequest() {}

    public DbConnectionConfig getDbConfig() { return dbConfig; }
    public void setDbConfig(DbConnectionConfig dbConfig) { this.dbConfig = dbConfig; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getRecordRootElement() { return recordRootElement; }
    public void setRecordRootElement(String recordRootElement) { this.recordRootElement = recordRootElement; }

    public int getSampleLinesLimit() { return sampleLinesLimit; }
    public void setSampleLinesLimit(int sampleLinesLimit) { this.sampleLinesLimit = sampleLinesLimit; }
}
