package com.enterprise.xmldbtool.model;

import java.util.*;

public class JobLaunchRequest {
    private DbConnectionConfig dbConfig;
    private String sourceXmlPath;
    private String recordRootElement = "record";
    private List<String> recordRootElements = new ArrayList<>();
    private String xmlVersion;
    private String schemaVersion;
    private String targetTable;
    private int chunkSize = 1000;
    private List<FieldMapping> mappings = new ArrayList<>();
    private String ingestMappingJson;
    private String ingestionJsonPath;
    private List<String> tableOrder = new ArrayList<>();
    private boolean stagedPipeline = true;
    private Long resumeFromJobExecutionId;
    private boolean resumeFromCheckpoint = false;
    private boolean retryFailedRecords = true;

    public JobLaunchRequest() {}

    public Long getResumeFromJobExecutionId() { return resumeFromJobExecutionId; }
    public void setResumeFromJobExecutionId(Long resumeFromJobExecutionId) { this.resumeFromJobExecutionId = resumeFromJobExecutionId; }

    public boolean isResumeFromCheckpoint() { return resumeFromCheckpoint; }
    public void setResumeFromCheckpoint(boolean resumeFromCheckpoint) { this.resumeFromCheckpoint = resumeFromCheckpoint; }

    public boolean isRetryFailedRecords() { return retryFailedRecords; }
    public void setRetryFailedRecords(boolean retryFailedRecords) { this.retryFailedRecords = retryFailedRecords; }

    public String getIngestMappingJson() { return ingestMappingJson; }
    public void setIngestMappingJson(String ingestMappingJson) { this.ingestMappingJson = ingestMappingJson; }

    public String getIngestionJsonPath() { return ingestionJsonPath; }
    public void setIngestionJsonPath(String ingestionJsonPath) { this.ingestionJsonPath = ingestionJsonPath; }

    public DbConnectionConfig getDbConfig() { return dbConfig; }
    public void setDbConfig(DbConnectionConfig dbConfig) { this.dbConfig = dbConfig; }

    public String getSourceXmlPath() { return sourceXmlPath; }
    public void setSourceXmlPath(String sourceXmlPath) { this.sourceXmlPath = sourceXmlPath; }

    public String getRecordRootElement() { return recordRootElement; }
    public void setRecordRootElement(String recordRootElement) { this.recordRootElement = recordRootElement; }

    public List<String> getRecordRootElements() {
        Set<String> set = new LinkedHashSet<>();
        if (recordRootElements != null) {
            for (String r : recordRootElements) {
                if (r != null && !r.trim().isEmpty()) {
                    for (String part : r.split("[,;\\s]+")) {
                        String s = com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.resolveEntityTag(part);
                        if (s != null && !s.isEmpty() && !com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.isWrapperContainerTag(s, null)) {
                            set.add(s);
                        }
                    }
                }
            }
        }
        if (recordRootElement != null && !recordRootElement.trim().isEmpty()) {
            for (String part : recordRootElement.split("[,;\\s]+")) {
                String s = com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.resolveEntityTag(part);
                if (s != null && !s.isEmpty() && !com.enterprise.xmldbtool.service.XmlSchemaDiscoveryService.isWrapperContainerTag(s, null)) {
                    set.add(s);
                }
            }
        }
        return set.isEmpty() ? List.of("record") : new ArrayList<>(set);
    }

    public void setRecordRootElements(List<String> recordRootElements) {
        this.recordRootElements = recordRootElements;
    }

    public String getXmlVersion() { return xmlVersion; }
    public void setXmlVersion(String xmlVersion) { this.xmlVersion = xmlVersion; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public List<FieldMapping> getMappings() { return mappings; }
    public void setMappings(List<FieldMapping> mappings) { this.mappings = mappings; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    public boolean isStagedPipeline() { return stagedPipeline; }
    public void setStagedPipeline(boolean stagedPipeline) { this.stagedPipeline = stagedPipeline; }
}
