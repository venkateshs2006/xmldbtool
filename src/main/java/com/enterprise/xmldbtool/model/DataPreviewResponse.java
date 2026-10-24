package com.enterprise.xmldbtool.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataPreviewResponse {
    private boolean success;
    private String message;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> previewRows = new ArrayList<>();
    private String generatedSqlTemplate;

    // Multi-table previews: TableName -> TablePreviewData
    private Map<String, TablePreviewData> tablePreviews = new LinkedHashMap<>();
    private List<String> tableOrder = new ArrayList<>();

    public DataPreviewResponse() {}

    public DataPreviewResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<Map<String, Object>> getPreviewRows() { return previewRows; }
    public void setPreviewRows(List<Map<String, Object>> previewRows) { this.previewRows = previewRows; }

    public String getGeneratedSqlTemplate() { return generatedSqlTemplate; }
    public void setGeneratedSqlTemplate(String generatedSqlTemplate) { this.generatedSqlTemplate = generatedSqlTemplate; }

    public Map<String, TablePreviewData> getTablePreviews() { return tablePreviews; }
    public void setTablePreviews(Map<String, TablePreviewData> tablePreviews) { this.tablePreviews = tablePreviews; }

    public List<String> getTableOrder() { return tableOrder; }
    public void setTableOrder(List<String> tableOrder) { this.tableOrder = tableOrder; }

    private boolean previewSkipped;
    private boolean dummyData;
    private long fileSizeBytes;
    private String fileSizeFormatted;
    private String skipReason;

    public boolean isPreviewSkipped() { return previewSkipped; }
    public void setPreviewSkipped(boolean previewSkipped) { this.previewSkipped = previewSkipped; }

    public boolean isDummyData() { return dummyData; }
    public void setDummyData(boolean dummyData) { this.dummyData = dummyData; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getFileSizeFormatted() { return fileSizeFormatted; }
    public void setFileSizeFormatted(String fileSizeFormatted) { this.fileSizeFormatted = fileSizeFormatted; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
}
