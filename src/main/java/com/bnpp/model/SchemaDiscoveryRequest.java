package com.bnpp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaDiscoveryRequest {
    private String xmlFilePath;
    private int sampleLimit = 100;
    private DbConnectionConfig dbConfig;

    public SchemaDiscoveryRequest() {}

    public SchemaDiscoveryRequest(String xmlFilePath) {
        this.xmlFilePath = xmlFilePath;
    }

    public String getXmlFilePath() {
        return xmlFilePath;
    }

    public void setXmlFilePath(String xmlFilePath) {
        this.xmlFilePath = xmlFilePath;
    }

    public int getSampleLimit() {
        return sampleLimit;
    }

    public void setSampleLimit(int sampleLimit) {
        this.sampleLimit = sampleLimit;
    }

    public DbConnectionConfig getDbConfig() {
        return dbConfig;
    }

    public void setDbConfig(DbConnectionConfig dbConfig) {
        this.dbConfig = dbConfig;
    }
}
